package yjs

// handler.go — WebSocket HTTP handler for the /ws/ide/{sessionId} endpoint.
//
// D-186: Accepts binary WebSocket frames following the y-websocket sync
// protocol. On connect:
//   1. Validate JWT (reusing the shared ep-common-go validator)
//   2. Upgrade to WebSocket (pure-stdlib, mirroring ws/handler.go)
//   3. GetOrCreate the YjsRoom for {sessionId}
//   4. Join the room as a Client
//   5. Send SYNC_STEP1 reply (the current document snapshot) to the new client
//   6. Read loop: HandleClientMessage for each inbound binary frame
//
// On disconnect: Leave the room; if the room is empty, it is removed from the
// registry (the durable state is persisted by the snapshot store, so dropping
// the in-memory copy is safe).

import (
	"fmt"
	"log"
	"net/http"
	"strings"
	"sync/atomic"
	"time"

	commonjwt "github.com/education-platform/ep-common-go/jwt"
)

var yjsConnIDCounter uint64

func nextYjsConnID() string {
	n := atomic.AddUint64(&yjsConnIDCounter, 1)
	return fmt.Sprintf("yjs-conn-%d-%d", time.Now().UnixNano(), n)
}

// Handler is the HTTP handler for /ws/ide/{sessionId}. It owns a RoomRegistry
// and an optional JWT validator.
type Handler struct {
	registry       *RoomRegistry
	validator      *commonjwt.Validator
	allowAnonymous bool
}

// NewHandler creates a Yjs WebSocket handler.
func NewHandler(registry *RoomRegistry, validator *commonjwt.Validator, allowAnonymous bool) *Handler {
	if registry == nil {
		registry = NewRoomRegistry()
	}
	return &Handler{
		registry:       registry,
		validator:      validator,
		allowAnonymous: allowAnonymous,
	}
}

// Registry returns the handler's room registry (test visibility).
func (h *Handler) Registry() *RoomRegistry { return h.registry }

// ServeHTTP handles the WebSocket upgrade and Yjs sync loop.
func (h *Handler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	// 1. Extract sessionId from the path: /ws/ide/{sessionId}
	sessionID := extractSessionID(r.URL.Path)
	if sessionID == "" {
		http.Error(w, "missing sessionId in path", http.StatusBadRequest)
		return
	}

	// 2. Validate JWT
	token := r.URL.Query().Get("token")
	if token == "" {
		auth := r.Header.Get("Authorization")
		if len(auth) > 7 && auth[:7] == "Bearer " {
			token = auth[7:]
		}
	}

	var userID string
	if h.validator != nil {
		claims, err := h.validator.Validate(token)
		if err != nil || claims == nil {
			if !h.allowAnonymous {
				http.Error(w, "unauthorized: invalid token", http.StatusUnauthorized)
				return
			}
			userID = "anonymous"
		} else {
			userID = claims.Subject
			if userID == "" {
				userID = "unknown"
			}
		}
	} else if !h.allowAnonymous {
		http.Error(w, "unauthorized: no validator configured", http.StatusUnauthorized)
		return
	} else {
		userID = "anonymous"
	}

	// 3. WebSocket upgrade headers
	if !isWebSocketUpgrade(r.Header) {
		http.Error(w, "not a websocket request", http.StatusBadRequest)
		return
	}
	key := r.Header.Get("Sec-WebSocket-Key")
	if key == "" {
		http.Error(w, "missing Sec-WebSocket-Key", http.StatusBadRequest)
		return
	}
	w.Header().Set("Upgrade", "websocket")
	w.Header().Set("Connection", "Upgrade")
	w.Header().Set("Sec-WebSocket-Accept", computeAcceptKey(key))
	w.WriteHeader(http.StatusSwitchingProtocols)

	// 4. Hijack the TCP connection
	hj, ok := w.(http.Hijacker)
	if !ok {
		http.Error(w, "websocket hijack not supported", http.StatusInternalServerError)
		return
	}
	conn, rw, err := hj.Hijack()
	if err != nil {
		log.Printf("yjs: hijack error: %v", err)
		return
	}
	defer conn.Close()

	// 5. Join the room
	room := h.registry.GetOrCreate(sessionID)
	clientID := nextYjsConnID()

	// sendCh is the per-connection outbound buffer. The write pump drains it.
	// st coordinates close/send so a concurrent BroadcastUpdate can never panic
	// on "send on closed channel" (P0-1).
	st := newClientState()
	closed := make(chan struct{})

	client := &Client{
		ID:     clientID,
		UserID: userID,
		Send:   st.trySend,
	}

	if !room.Join(client) {
		log.Printf("yjs: duplicate client %s in room %s", clientID, sessionID)
		return
	}
	defer func() {
		room.Leave(clientID)
		if room.ClientCount() == 0 {
			h.registry.Remove(sessionID)
		}
		close(closed)
	}()

	log.Printf("yjs: client %s (user=%s) joined room %s (%d clients)",
		clientID, userID, sessionID, room.ClientCount())

	// 6. Send SYNC_STEP1 reply — the current document snapshot — to the new
	//    client so it can initialise its local CRDT state.
	room.mu.RLock()
	snapshot := room.doc.Snapshot()
	room.mu.RUnlock()
	reply := make([]byte, 1+len(snapshot))
	reply[0] = MsgSyncStep2
	copy(reply[1:], snapshot)
	// best-effort write; if it fails the read loop will error out
	if err := writeFrame(rw, 2, reply); err != nil { // opcode 2 = binary
		log.Printf("yjs: initial snapshot write failed for %s: %v", clientID, err)
		return
	}

	// 7. Read pump: read binary frames, dispatch to the room
	go func() {
		defer func() {
			if r := recover(); r != nil {
				log.Printf("[panic-recovery] yjs read pump panicked: %v", r)
			}
			st.closeAndDrain()
		}()
		buf := make([]byte, 65536)
		for {
			n, err := rw.Read(buf)
			if err != nil {
				return
			}
			payload, err := parseFramePayload(buf[:n])
			if err != nil || payload == nil {
				continue
			}
			room.HandleClientMessage(clientID, payload)
		}
	}()

	// 8. Write pump: drain sendCh to the wire
	for {
		select {
		case msg, ok := <-st.sendCh:
			if !ok {
				return
			}
			if err := writeFrame(rw, 2, msg); err != nil { // binary
				return
			}
		case <-closed:
			return
		}
	}
}

// extractSessionID pulls the {sessionId} segment from /ws/ide/{sessionId}.
func extractSessionID(path string) string {
	// strip query string if present
	if i := strings.IndexByte(path, '?'); i >= 0 {
		path = path[:i]
	}
	parts := strings.Split(strings.Trim(path, "/"), "/")
	// expect: ["ws", "ide", "{sessionId}"]
	if len(parts) >= 3 && parts[0] == "ws" && parts[1] == "ide" {
		return parts[2]
	}
	return ""
}

// isWebSocketUpgrade checks for the required WebSocket upgrade headers.
func isWebSocketUpgrade(hh http.Header) bool {
	return hh.Get("Upgrade") == "websocket" && hh.Get("Connection") == "Upgrade"
}
