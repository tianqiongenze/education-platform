// Package api — ws.go implements the WebSocket handler for /ws/live/{roomId}
// (D-187).
//
// The handler reuses the pure-stdlib WebSocket framing from ws-gateway (no
// third-party WebSocket library, to stay buildable in the offline environment).
// On connect it registers a danmaku subscriber and starts the room's batcher;
// on disconnect it unregisters and tears down the batcher when the last
// subscriber leaves.
//
// Message protocol (JSON):
//   inbound  (client → server): {"message":"hello"} → Submit to pipeline
//   outbound (server → client): {"roomId":"...","messages":[{...},{...}]}
package api

import (
	"context"
	"crypto/sha1"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"strconv"
	"sync"
	"sync/atomic"
	"time"

	"github.com/education-platform/live-go/danmaku"
)

// wsGUID is the RFC 6455 magic GUID for the Sec-WebSocket-Accept handshake.
const wsGUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

var wsConnCounter uint64

// WSHandler is the /ws/live/{roomId} handler.
type WSHandler struct {
	pipeline *danmaku.DanmakuPipeline

	// per-room batcher lifecycle: roomID → cancel func + refcount.
	mu       sync.Mutex
	batchers map[string]*roomBatcher
}

type roomBatcher struct {
	cancel  context.CancelFunc
	refs    int
}

// NewWSHandler builds a WebSocket handler bound to a danmaku pipeline.
func NewWSHandler(pipeline *danmaku.DanmakuPipeline) *WSHandler {
	return &WSHandler{
		pipeline:  pipeline,
		batchers:  map[string]*roomBatcher{},
	}
}

// Register wires the WS route onto the mux.
func (h *WSHandler) Register(mux *http.ServeMux) {
	mux.HandleFunc("/ws/live/", h.ServeHTTP)
}

// ServeHTTP handles the WebSocket upgrade + read/write pumps.
func (h *WSHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	roomID := r.PathValue("roomId")
	if roomID == "" {
		// Fall back to path suffix for the "/ws/live/" prefix registration.
		roomID = trimPrefix(r.URL.Path, "/ws/live/")
	}
	if roomID == "" {
		http.Error(w, "missing roomId", http.StatusBadRequest)
		return
	}

	if !isWebSocketUpgrade(r.Header) {
		http.Error(w, "not a websocket request", http.StatusBadRequest)
		return
	}
	key := r.Header.Get("Sec-WebSocket-Key")
	if key == "" {
		http.Error(w, "missing Sec-WebSocket-Key", http.StatusBadRequest)
		return
	}

	// RFC 6455 Section 4.2.2 handshake.
	accept := computeAcceptKey(key)
	w.Header().Set("Upgrade", "websocket")
	w.Header().Set("Connection", "Upgrade")
	w.Header().Set("Sec-WebSocket-Accept", accept)
	w.WriteHeader(http.StatusSwitchingProtocols)

	hj, ok := w.(http.Hijacker)
	if !ok {
		http.Error(w, "websocket hijack not supported", http.StatusInternalServerError)
		return
	}
	conn, rw, err := hj.Hijack()
	if err != nil {
		log.Printf("live-go ws: hijack error: %v", err)
		return
	}
	defer conn.Close()

	userID := r.URL.Query().Get("userId")
	if userID == "" {
		userID = strconv.FormatInt(int64(atomic.AddUint64(&wsConnCounter, 1)), 10)
	}

	// Acquire batcher for this room (start if first subscriber).
	h.acquireBatcher(r.Context(), roomID)
	defer h.releaseBatcher(roomID)

	subCh, unsub := h.pipeline.Subscribe(roomID)
	defer unsub()

	// Send a connected ack.
	ack, _ := json.Marshal(map[string]any{"type": "connected", "roomId": roomID, "userId": userID})
	if err := writeFrame(rw, 1, ack); err != nil {
		return
	}

	done := make(chan struct{})
	// Write pump: forward batched danmaku to the client.
	go func() {
		defer close(done)
		for payload := range subCh {
			if err := writeFrame(rw, 1, payload); err != nil {
				return
			}
		}
	}()

	// Read pump: decode inbound chat → Submit to pipeline.
	buf := make([]byte, 4096)
	for {
		n, err := rw.Read(buf)
		if err != nil {
			break
		}
		payload, err := parseFramePayload(buf[:n])
		if err != nil || payload == nil {
			continue
		}
		opcode, _ := parseFrameOpcode(buf[:n])
		if opcode == 0x8 { // close
			break
		}
		if opcode == 0x9 { // ping → pong
			_ = writeFrame(rw, 0xA, payload)
			continue
		}
		var msg struct {
			Message string `json:"message"`
		}
		if err := json.Unmarshal(payload, &msg); err != nil {
			continue
		}
		if msg.Message == "" {
			continue
		}
		ctx, cancel := context.WithTimeout(r.Context(), 2*time.Second)
		if err := h.pipeline.Submit(ctx, roomID, userID, msg.Message); err != nil {
			log.Printf("live-go ws: submit danmaku: %v", err)
		}
		cancel()
	}
	<-done
}

// acquireBatcher starts the room's batcher if it's not running and bumps the
// refcount. Each acquire must be paired with a releaseBatcher.
func (h *WSHandler) acquireBatcher(parent context.Context, roomID string) {
	h.mu.Lock()
	defer h.mu.Unlock()
	rb, ok := h.batchers[roomID]
	if !ok {
		ctx, cancel := context.WithCancel(parent)
		h.pipeline.StartBatcher(ctx, roomID)
		rb = &roomBatcher{cancel: cancel}
		h.batchers[roomID] = rb
	}
	rb.refs++
}

// releaseBatcher decrements the refcount and tears down the batcher when the
// last subscriber leaves.
func (h *WSHandler) releaseBatcher(roomID string) {
	h.mu.Lock()
	defer h.mu.Unlock()
	rb, ok := h.batchers[roomID]
	if !ok {
		return
	}
	rb.refs--
	if rb.refs <= 0 {
		rb.cancel()
		delete(h.batchers, roomID)
	}
}

// --- pure-stdlib WebSocket frame helpers (ported from ws-gateway) ---

func isWebSocketUpgrade(hdr http.Header) bool {
	return hdr.Get("Upgrade") == "websocket" && hdr.Get("Connection") == "Upgrade"
}

func computeAcceptKey(key string) string {
	h := sha1.New()
	h.Write([]byte(key + wsGUID))
	return base64.StdEncoding.EncodeToString(h.Sum(nil))
}

// writeFrame writes a server→client frame (unmasked, per RFC 6455 5.1).
func writeFrame(w io.Writer, opcode int, payload []byte) error {
	var header [14]byte
	header[0] = 0x80 | byte(opcode) // FIN=1 + opcode
	pos := 2
	l := len(payload)
	switch {
	case l < 126:
		header[1] = byte(l)
	case l < 65536:
		header[1] = 126
		binary.BigEndian.PutUint16(header[2:4], uint16(l))
		pos = 4
	default:
		header[1] = 127
		binary.BigEndian.PutUint64(header[2:10], uint64(l))
		pos = 10
	}
	if _, err := w.Write(header[:pos]); err != nil {
		return fmt.Errorf("ws: write header: %w", err)
	}
	if _, err := w.Write(payload); err != nil {
		return fmt.Errorf("ws: write payload: %w", err)
	}
	return nil
}

// parseFramePayload unmaskes a client→server frame.
func parseFramePayload(data []byte) ([]byte, error) {
	if len(data) < 2 {
		return nil, fmt.Errorf("ws: frame too short")
	}
	masked := data[1]&0x80 != 0
	payloadLen := int(data[1] & 0x7f)
	pos := 2
	switch payloadLen {
	case 126:
		if len(data) < 4 {
			return nil, fmt.Errorf("ws: extended16 frame too short")
		}
		payloadLen = int(binary.BigEndian.Uint16(data[2:4]))
		pos = 4
	case 127:
		if len(data) < 10 {
			return nil, fmt.Errorf("ws: extended64 frame too short")
		}
		payloadLen = int(binary.BigEndian.Uint64(data[2:10]))
		pos = 10
	}
	var mask [4]byte
	if masked {
		if len(data) < pos+4 {
			return nil, fmt.Errorf("ws: mask key missing")
		}
		copy(mask[:], data[pos:pos+4])
		pos += 4
	}
	if len(data) < pos+payloadLen {
		return nil, fmt.Errorf("ws: payload incomplete")
	}
	payload := make([]byte, payloadLen)
	copy(payload, data[pos:pos+payloadLen])
	if masked {
		for i := range payload {
			payload[i] ^= mask[i%4]
		}
	}
	return payload, nil
}

func parseFrameOpcode(data []byte) (int, error) {
	if len(data) < 1 {
		return 0, fmt.Errorf("ws: empty frame")
	}
	return int(data[0] & 0x0f), nil
}

// trimPrefix returns s with prefix removed, or s unchanged if no prefix.
func trimPrefix(s, prefix string) string {
	if len(s) >= len(prefix) && s[:len(prefix)] == prefix {
		return s[len(prefix):]
	}
	return s
}
