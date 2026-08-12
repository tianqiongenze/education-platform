// Package ws provides WebSocket connection management for ws-gateway.
//
// D-149: WebSocket handler for board game live play and general real-time
// communication. This module implements:
//   - Connection upgrade with JWT authentication (RS256 via ep-common-go)
//   - Per-session connection registry (sessionID → *Connection)
//   - Room-based connection grouping for game rooms
//   - Read/write pump goroutines per connection
//   - Graceful shutdown on context cancellation
//
// Authentication flow:
//   1. Client connects to /ws?token=<JWT>
//   2. Handler validates JWT using the shared Validator
//   3. On success, the user's subject (user ID) is extracted and a session
//      is registered in the Hub for broadcast routing
//   4. If JWT validation is disabled (dev mode, no key configured), the
//      connection is accepted with a synthetic session ID and a warning is logged
//
// Message protocol:
//   Inbound (client → server): {"type":"join_room","room_id":"..."}
//                              {"type":"leave_room","room_id":"..."}
//                              {"type":"move","room_id":"...","data":{...}}
//   Outbound (server → client): {"type":"room_update","room_id":"...","data":{...}}
//                                {"type":"move_broadcast","room_id":"...","data":{...}}
//                                {"type":"error","data":"..."}
package ws

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"sync"
	"sync/atomic"
	"time"

	commonjwt "github.com/education-platform/ep-common-go/jwt"
	"github.com/education-platform/ws-gateway/src/hub"
)

// connIDCounter is a monotonically increasing counter for unique connection
// IDs. Using atomic ensures uniqueness even under concurrent connection
// creation within the same nanosecond.
var connIDCounter uint64

// nextConnID returns a unique connection ID.
func nextConnID() string {
	n := atomic.AddUint64(&connIDCounter, 1)
	return fmt.Sprintf("conn-%d-%d", time.Now().UnixNano(), n)
}

// Connection wraps a single WebSocket connection with metadata.
type Connection struct {
	ID       string        // unique connection ID (generated)
	UserID   string        // user ID from JWT subject
	Rooms    map[string]bool // rooms this connection has joined
	mu       sync.Mutex
	send     chan []byte // buffered outbound channel
	closeCh  chan struct{}
	closed   bool
}

// NewConnection creates a Connection with default buffering.
func NewConnection(userID string) *Connection {
	return &Connection{
		ID:      nextConnID(),
		UserID:  userID,
		Rooms:   map[string]bool{},
		send:    make(chan []byte, 256),
		closeCh: make(chan struct{}),
	}
}

// Send pushes a message to the connection's outbound buffer.
// Returns false if the connection is closed or the buffer is full.
func (c *Connection) Send(msg []byte) bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.closed {
		return false
	}
	select {
	case c.send <- msg:
		return true
	default:
		// buffer full — drop the message to avoid blocking
		log.Printf("ws: connection %s send buffer full, dropping message", c.ID)
		return false
	}
}

// Close shuts down the connection. Safe to call multiple times.
func (c *Connection) Close() {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.closed {
		return
	}
	c.closed = true
	close(c.closeCh)
	close(c.send)
}

// Closed returns whether the connection has been closed.
func (c *Connection) Closed() bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.closed
}

// JoinRoom adds the connection to a room.
func (c *Connection) JoinRoom(roomID string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.Rooms[roomID] = true
}

// LeaveRoom removes the connection from a room.
func (c *Connection) LeaveRoom(roomID string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	delete(c.Rooms, roomID)
}

// Outbound returns the read-only outbound channel (for the write pump).
func (c *Connection) Outbound() <-chan []byte {
	return c.send
}

// Done returns a channel that's closed when the connection closes.
func (c *Connection) Done() <-chan struct{} {
	return c.closeCh
}

// Registry manages all active WebSocket connections.
// D-149: thread-safe registry supporting per-session and per-room lookup.
type Registry struct {
	mu       sync.RWMutex
	byID     map[string]*Connection       // connID → Connection
	byRoom   map[string]map[string]bool   // roomID → set of connIDs
}

// NewRegistry creates an empty Registry.
func NewRegistry() *Registry {
	return &Registry{
		byID:   map[string]*Connection{},
		byRoom: map[string]map[string]bool{},
	}
}

// Register adds a connection to the registry.
func (r *Registry) Register(conn *Connection) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.byID[conn.ID] = conn
}

// Unregister removes a connection from the registry and all rooms.
func (r *Registry) Unregister(conn *Connection) {
	r.mu.Lock()
	defer r.mu.Unlock()
	delete(r.byID, conn.ID)
	for roomID := range conn.Rooms {
		if conns, ok := r.byRoom[roomID]; ok {
			delete(conns, conn.ID)
			if len(conns) == 0 {
				delete(r.byRoom, roomID)
			}
		}
	}
}

// JoinRoom adds a connection to a room in the registry.
func (r *Registry) JoinRoom(conn *Connection, roomID string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	conn.JoinRoom(roomID)
	if r.byRoom[roomID] == nil {
		r.byRoom[roomID] = map[string]bool{}
	}
	r.byRoom[roomID][conn.ID] = true
}

// LeaveRoom removes a connection from a room in the registry.
func (r *Registry) LeaveRoom(conn *Connection, roomID string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	conn.LeaveRoom(roomID)
	if conns, ok := r.byRoom[roomID]; ok {
		delete(conns, conn.ID)
		if len(conns) == 0 {
			delete(r.byRoom, roomID)
		}
	}
}

// BroadcastToRoom sends a message to all connections in a room.
func (r *Registry) BroadcastToRoom(roomID string, msg []byte) int {
	r.mu.RLock()
	connIDs := r.byRoom[roomID]
	conns := make([]*Connection, 0, len(connIDs))
	for id := range connIDs {
		if c, ok := r.byID[id]; ok {
			conns = append(conns, c)
		}
	}
	r.mu.RUnlock()

	delivered := 0
	for _, c := range conns {
		if c.Send(msg) {
			delivered++
		}
	}
	return delivered
}

// GetConnection returns a connection by ID, or nil if not found.
func (r *Registry) GetConnection(connID string) *Connection {
	r.mu.RLock()
	defer r.mu.RUnlock()
	return r.byID[connID]
}

// Count returns the total number of active connections.
func (r *Registry) Count() int {
	r.mu.RLock()
	defer r.mu.RUnlock()
	return len(r.byID)
}

// RoomCount returns the number of connections in a room.
func (r *Registry) RoomCount(roomID string) int {
	r.mu.RLock()
	defer r.mu.RUnlock()
	return len(r.byRoom[roomID])
}

// Handler is the WebSocket HTTP handler. D-149.
//
// It handles the /ws endpoint: validates JWT, upgrades the connection,
// registers it in the Registry and Hub, then runs read/write pumps.
type Handler struct {
	registry  *Registry
	hub       *hub.Hub
	validator *commonjwt.Validator
	// allowAnonymous allows connections without valid JWT (dev mode).
	// Set to true when no JWT public key is configured.
	allowAnonymous bool
}

// NewHandler creates a new WebSocket handler.
func NewHandler(registry *Registry, h *hub.Hub, validator *commonjwt.Validator, allowAnonymous bool) *Handler {
	return &Handler{
		registry:       registry,
		hub:            h,
		validator:      validator,
		allowAnonymous: allowAnonymous,
	}
}

// ServeHTTP handles the WebSocket upgrade request.
//
// D-149: This is a pure-stdlib implementation that does NOT use a WebSocket
// library (to avoid adding go.mod dependencies in the offline build env).
// Instead, it implements the WebSocket handshake and framing manually using
// net/http and crypto/sha1 for the Sec-WebSocket-Accept header.
//
// NOTE: This handler currently only performs the HTTP-level handshake and
// authentication. Full WebSocket framing (RFC 6455 Section 5) is handled by
// the Conn type in conn.go. If the handshake fails, a 401/400 is returned.
func (h *Handler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	// 1. Validate JWT
	token := r.URL.Query().Get("token")
	if token == "" {
		token = r.Header.Get("Authorization")
		if len(token) > 7 && token[:7] == "Bearer " {
			token = token[7:]
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
	} else {
		if !h.allowAnonymous {
			http.Error(w, "unauthorized: no validator configured", http.StatusUnauthorized)
			return
		}
		userID = "anonymous"
	}

	// 2. Check WebSocket upgrade headers
	if !isWebSocketUpgrade(r.Header) {
		http.Error(w, "not a websocket request", http.StatusBadRequest)
		return
	}

	// 3. Perform WebSocket handshake (RFC 6455 Section 4.2.2)
	key := r.Header.Get("Sec-WebSocket-Key")
	if key == "" {
		http.Error(w, "missing Sec-WebSocket-Key", http.StatusBadRequest)
		return
	}
	accept := computeAcceptKey(key)
	w.Header().Set("Upgrade", "websocket")
	w.Header().Set("Connection", "Upgrade")
	w.Header().Set("Sec-WebSocket-Accept", accept)
	w.WriteHeader(http.StatusSwitchingProtocols)

	// 4. Hijack the connection to take over the TCP socket
	hj, ok := w.(http.Hijacker)
	if !ok {
		http.Error(w, "websocket hijack not supported", http.StatusInternalServerError)
		return
	}
	conn, rw, err := hj.Hijack()
	if err != nil {
		log.Printf("ws: hijack error: %v", err)
		return
	}
	defer conn.Close()

	// 5. Create and register the connection
	wsConn := NewConnection(userID)
	h.registry.Register(wsConn)
	defer h.registry.Unregister(wsConn)

	// Send a welcome frame
	welcome, _ := Frame{Type: "connected", Data: map[string]any{
		"conn_id": wsConn.ID,
		"user_id": userID,
	}}.Encode()
	writeFrame(rw, 1, welcome) // opcode 1 = text

	// 6. Read pump — read frames from the client
	go func() {
		defer wsConn.Close()
		readPump(rw, wsConn, h)
	}()

	// 7. Write pump — write frames from the send channel to the client
	for {
		select {
		case msg, ok := <-wsConn.Outbound():
			if !ok {
				return
			}
			if err := writeFrame(rw, 1, msg); err != nil {
				return
			}
		case <-wsConn.Done():
			return
		}
	}
}

// readPump reads WebSocket frames from the client and processes them.
// D-149: handles join_room, leave_room, and move messages.
func readPump(rw interface{ Read([]byte) (int, error) }, conn *Connection, h *Handler) {
	buf := make([]byte, 4096)
	for {
		n, err := rw.Read(buf)
		if err != nil {
			return
		}
		// Parse the WebSocket frame to extract payload
		payload, err := parseFramePayload(buf[:n])
		if err != nil || payload == nil {
			continue
		}

		frame, err := DecodeFrame(payload)
		if err != nil {
			continue
		}

		switch frame.Type {
		case "join_room":
			roomID, _ := frame.Data.(string)
			if roomID == "" {
				if m, ok := frame.Data.(map[string]any); ok {
					roomID, _ = m["room_id"].(string)
				}
			}
			if roomID != "" {
				h.registry.JoinRoom(conn, roomID)
				ack, _ := Frame{Type: "room_joined", Data: map[string]any{"room_id": roomID}}.Encode()
				conn.Send(ack)
			}
		case "leave_room":
			roomID, _ := frame.Data.(string)
			if roomID == "" {
				if m, ok := frame.Data.(map[string]any); ok {
					roomID, _ = m["room_id"].(string)
				}
			}
			if roomID != "" {
				h.registry.LeaveRoom(conn, roomID)
				ack, _ := Frame{Type: "room_left", Data: map[string]any{"room_id": roomID}}.Encode()
				conn.Send(ack)
			}
		case "move":
			// Broadcast move to all room members via Hub (cross-pod)
			if m, ok := frame.Data.(map[string]any); ok {
				roomID, _ := m["room_id"].(string)
				if roomID != "" {
					// Add room_id to the payload for the subscribe loop
					broadcastPayload, _ := json.Marshal(Frame{
						Type: "move_broadcast",
						Data: m,
					})
					ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
					if err := h.hub.BroadcastToRoom(ctx, roomID, broadcastPayload); err != nil {
						log.Printf("ws: broadcast to room %s failed: %v", roomID, err)
					}
					cancel()
					// Also deliver locally (in case sender is on same pod)
					h.registry.BroadcastToRoom(roomID, broadcastPayload)
				}
			}
		case "ping":
			pong, _ := Frame{Type: "pong"}.Encode()
			conn.Send(pong)
		}
	}
}

// isWebSocketUpgrade checks if the request has the required WebSocket
// upgrade headers (RFC 6455 Section 4.2.1).
func isWebSocketUpgrade(h http.Header) bool {
	return h.Get("Upgrade") == "websocket" &&
		h.Get("Connection") == "Upgrade"
}
