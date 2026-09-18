// Package yjs provides the Yjs collaborative document WebSocket handler
// for ws-gateway.
//
// D-186: The /ws/ide/{sessionId} endpoint accepts binary WebSocket frames
// following the y-websocket sync protocol. Each session maps to a YjsRoom
// that holds the in-memory Yjs document state, fans out updates to all
// connected collaborators, and persists incremental snapshots.
//
// This implementation mirrors the Java-side YjsDocument/YjsSyncHandler
// (grading-service) — the server treats updates as opaque binary blobs and
// appends them to an in-memory log. CRDT merging is performed client-side by
// the official yjs JS library; the server only coordinates fan-out and
// snapshot persistence.
package yjs

import (
	"log"
	"sync"
)

// y-websocket sync protocol message types.
const (
	MsgSyncStep1      byte = 0
	MsgSyncStep2      byte = 1
	MsgAwarenessUpdate byte = 2
	MsgAwarenessQuery  byte = 3
	MsgUpdate          byte = 4
)

// YjsDocument is the server-side append-only log of binary update frames.
// It is NOT a full CRDT — it stores raw update blobs so reconnecting clients
// can replay them. The actual CRDT merge logic lives in the client-side yjs
// library.
//
// Not thread-safe; the owning YjsRoom serialises access via its mutex.
type YjsDocument struct {
	docID            string
	updates          [][]byte
	awarenessUpdates [][]byte
}

// NewYjsDocument creates an empty document for the given doc id.
func NewYjsDocument(docID string) *YjsDocument {
	return &YjsDocument{docID: docID}
}

// DocID returns the document id.
func (d *YjsDocument) DocID() string { return d.docID }

// UpdateCount returns the number of document updates applied.
func (d *YjsDocument) UpdateCount() int { return len(d.updates) }

// ApplyUpdate appends a binary update to the log.
func (d *YjsDocument) ApplyUpdate(update []byte) {
	if len(update) == 0 {
		return
	}
	// clone to avoid aliasing the caller's slice
	dup := make([]byte, len(update))
	copy(dup, update)
	d.updates = append(d.updates, dup)
}

// ApplyAwarenessUpdate appends an awareness update.
func (d *YjsDocument) ApplyAwarenessUpdate(update []byte) {
	if len(update) == 0 {
		return
	}
	dup := make([]byte, len(update))
	copy(dup, update)
	d.awarenessUpdates = append(d.awarenessUpdates, dup)
}

// Snapshot returns the flattened concatenation of all document updates.
func (d *YjsDocument) Snapshot() []byte {
	return flatten(d.updates)
}

// Awareness returns the flattened concatenation of all awareness updates.
func (d *YjsDocument) Awareness() []byte {
	return flatten(d.awarenessUpdates)
}

// flatten concatenates a slice of byte slices into a single contiguous slice.
func flatten(chunks [][]byte) []byte {
	if len(chunks) == 0 {
		return []byte{}
	}
	total := 0
	for _, c := range chunks {
		total += len(c)
	}
	out := make([]byte, 0, total)
	for _, c := range chunks {
		out = append(out, c...)
	}
	return out
}

// HandleSyncMessage processes an inbound binary message and returns the
// optional reply bytes for the sender. The message format is:
//
//	[type:1 byte][payload:N bytes]
//
// Returns (reply, true) when a reply should be sent to the sender, or
// (nil, false) when no reply is needed (e.g. UPDATE / AWARENESS_UPDATE are
// broadcast by the room, not answered directly).
func (d *YjsDocument) HandleSyncMessage(message []byte) ([]byte, bool) {
	if len(message) == 0 {
		return nil, false
	}
	msgType := message[0]
	payload := message[1:]

	switch msgType {
	case MsgSyncStep1:
		// Reply with SYNC_STEP2 containing the current snapshot
		reply := make([]byte, 1+len(d.Snapshot()))
		reply[0] = MsgSyncStep2
		copy(reply[1:], d.Snapshot())
		return reply, true

	case MsgSyncStep2:
		d.ApplyUpdate(payload)
		return nil, false

	case MsgAwarenessUpdate:
		d.ApplyAwarenessUpdate(payload)
		return nil, false

	case MsgAwarenessQuery:
		reply := make([]byte, 1+len(d.Awareness()))
		reply[0] = MsgAwarenessUpdate
		copy(reply[1:], d.Awareness())
		return reply, true

	case MsgUpdate:
		d.ApplyUpdate(payload)
		return nil, false

	default:
		return nil, false
	}
}

// EncodeUpdate prepends the UPDATE message type to a payload.
func EncodeUpdate(payload []byte) []byte {
	return prependType(MsgUpdate, payload)
}

// EncodeAwareness prepends the AWARENESS_UPDATE message type to a payload.
func EncodeAwareness(payload []byte) []byte {
	return prependType(MsgAwarenessUpdate, payload)
}

func prependType(t byte, payload []byte) []byte {
	out := make([]byte, 1+len(payload))
	out[0] = t
	copy(out[1:], payload)
	return out
}

// Client represents a single connected WebSocket client inside a YjsRoom.
type Client struct {
	ID     string
	UserID string
	Send   func([]byte) bool // outbound send callback
}

// clientState holds the send-channel and close coordination for a yjs
// connection. It is referenced by the Client.Send closure and the read pump's
// shutdown path. Mirrors the ws.Connection closed/mu/send pattern so that
// broadcasting to a closed client never panics on "send on closed channel".
type clientState struct {
	mu     sync.Mutex
	sendCh chan []byte
	closed bool
}

// newClientState creates a clientState with the standard 256-entry buffer.
func newClientState() *clientState {
	return &clientState{sendCh: make(chan []byte, 256)}
}

// trySend attempts to enqueue msg onto the send channel. Returns false if the
// connection is closed or the buffer is full (message dropped). Safe to call
// concurrently with closeAndDrain.
func (s *clientState) trySend(msg []byte) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		return false
	}
	select {
	case s.sendCh <- msg:
		return true
	default:
		log.Printf("yjs: send buffer full, dropping message")
		return false
	}
}

// closeAndDrain marks the connection closed and closes the send channel so the
// write pump exits. Safe to call multiple times. The mutex guarantees that no
// trySend is in flight between the closed check and the channel send, which is
// what prevents "send on closed channel" panics from concurrent broadcasts.
func (s *clientState) closeAndDrain() {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		return
	}
	s.closed = true
	close(s.sendCh)
}

// YjsRoom is a collaborative editing room keyed by session id. It owns one
// YjsDocument and the set of connected clients. All mutations are serialised
// by the embedded mutex.
//
// Iron Law 1: the room is an in-process ephemeral coordinator. Durable state
// is externalised to the YjsSnapshotStore (Redis) by the caller; the room
// itself is rebuilt from snapshots on pod restart.
type YjsRoom struct {
	sessionID string
	doc       *YjsDocument
	mu        sync.RWMutex
	clients   map[string]*Client
}

// NewYjsRoom creates an empty room for the given session id.
func NewYjsRoom(sessionID string) *YjsRoom {
	return &YjsRoom{
		sessionID: sessionID,
		doc:       NewYjsDocument(sessionID),
		clients:   map[string]*Client{},
	}
}

// SessionID returns the room's session id.
func (r *YjsRoom) SessionID() string { return r.sessionID }

// ClientCount returns the number of connected clients.
func (r *YjsRoom) ClientCount() int {
	r.mu.RLock()
	defer r.mu.RUnlock()
	return len(r.clients)
}

// Doc returns the room's YjsDocument. Callers MUST hold the room mutex when
// mutating the document; reads are safe without the lock for snapshot
// purposes (best-effort).
func (r *YjsRoom) Doc() *YjsDocument { return r.doc }

// Join adds a client to the room. Returns false if a client with the same id
// is already present.
func (r *YjsRoom) Join(c *Client) bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	if _, exists := r.clients[c.ID]; exists {
		return false
	}
	r.clients[c.ID] = c
	return true
}

// Leave removes a client from the room. Safe to call when the client is not
// present.
func (r *YjsRoom) Leave(clientID string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	delete(r.clients, clientID)
}

// BroadcastUpdate sends a binary message to every connected client except
// the one identified by exceptClientID (the originator). Returns the number
// of clients that received the message.
func (r *YjsRoom) BroadcastUpdate(message []byte, exceptClientID string) int {
	r.mu.RLock()
	clients := make([]*Client, 0, len(r.clients))
	for _, c := range r.clients {
		if c.ID != exceptClientID {
			clients = append(clients, c)
		}
	}
	r.mu.RUnlock()

	delivered := 0
	for _, c := range clients {
		if c.Send(message) {
			delivered++
		}
	}
	return delivered
}

// ApplyUpdate applies a binary update to the document under the room mutex
// and broadcasts it to all other clients. Returns the number of clients that
// received the broadcast.
func (r *YjsRoom) ApplyUpdate(update []byte, originClientID string) int {
	r.mu.Lock()
	r.doc.ApplyUpdate(update)
	r.mu.Unlock()
	encoded := EncodeUpdate(update)
	return r.BroadcastUpdate(encoded, originClientID)
}

// ApplyAwarenessUpdate applies an awareness update and broadcasts it.
func (r *YjsRoom) ApplyAwarenessUpdate(update []byte, originClientID string) int {
	r.mu.Lock()
	r.doc.ApplyAwarenessUpdate(update)
	r.mu.Unlock()
	encoded := EncodeAwareness(update)
	return r.BroadcastUpdate(encoded, originClientID)
}

// HandleClientMessage processes a message received from the given client.
// It applies updates to the document and broadcasts as needed. The optional
// reply (when non-nil) is sent only to the originator.
//
// Important: HandleSyncMessage already applies UPDATE / SYNC_STEP2 /
// AWARENESS_UPDATE to the document. Here we only broadcast to other peers;
// we must NOT re-apply the update (that would double-count it).
func (r *YjsRoom) HandleClientMessage(clientID string, message []byte) {
	r.mu.Lock()
	reply, _ := r.doc.HandleSyncMessage(message)
	r.mu.Unlock()

	// send reply to the originator only
	if reply != nil {
		r.mu.RLock()
		c, ok := r.clients[clientID]
		r.mu.RUnlock()
		if ok {
			c.Send(reply)
		}
		return
	}

	// For UPDATE / SYNC_STEP2 / AWARENESS_UPDATE the update was already
	// applied by HandleSyncMessage above. We only need to broadcast the
	// raw payload to other peers so they can merge it client-side.
	if len(message) == 0 {
		return
	}
	switch message[0] {
	case MsgUpdate:
		r.BroadcastUpdate(EncodeUpdate(message[1:]), clientID)
	case MsgAwarenessUpdate:
		r.BroadcastUpdate(EncodeAwareness(message[1:]), clientID)
	case MsgSyncStep2:
		// SYNC_STEP2 carries updates the client sent to the server; broadcast
		// them so other peers can merge.
		r.BroadcastUpdate(EncodeUpdate(message[1:]), clientID)
	}
}

// RoomRegistry is a process-local registry of active YjsRooms keyed by
// session id. It is thread-safe.
type RoomRegistry struct {
	mu    sync.RWMutex
	rooms map[string]*YjsRoom
}

// NewRoomRegistry creates an empty registry.
func NewRoomRegistry() *RoomRegistry {
	return &RoomRegistry{rooms: map[string]*YjsRoom{}}
}

// GetOrCreate returns the room for sessionID, creating it if absent.
func (reg *RoomRegistry) GetOrCreate(sessionID string) *YjsRoom {
	reg.mu.Lock()
	defer reg.mu.Unlock()
	if r, ok := reg.rooms[sessionID]; ok {
		return r
	}
	r := NewYjsRoom(sessionID)
	reg.rooms[sessionID] = r
	return r
}

// Get returns the room for sessionID or nil if not found.
func (reg *RoomRegistry) Get(sessionID string) *YjsRoom {
	reg.mu.RLock()
	defer reg.mu.RUnlock()
	return reg.rooms[sessionID]
}

// Remove deletes a room from the registry (e.g. when the last client leaves).
func (reg *RoomRegistry) Remove(sessionID string) {
	reg.mu.Lock()
	defer reg.mu.Unlock()
	delete(reg.rooms, sessionID)
}

// Count returns the number of active rooms.
func (reg *RoomRegistry) Count() int {
	reg.mu.RLock()
	defer reg.mu.RUnlock()
	return len(reg.rooms)
}
