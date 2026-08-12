package hub

import (
	"context"
	"fmt"
	"hash/fnv"
	"log"
	"sync"

	"github.com/redis/go-redis/v9"
)

// shardChannelFor computes the shard channel name for a given session ID.
// Uses FNV-1a hash modulo shard count for even distribution.
func shardChannelFor(sessionID string, shards int) string {
	h := fnv.New32a()
	h.Write([]byte(sessionID))
	return fmt.Sprintf("ep:ws:shard:%d", h.Sum32()%uint32(shards))
}

// roomChannel returns the Redis Pub/Sub channel name for a game/match room.
// D-149: board game rooms use a dedicated channel namespace so broadcast
// targets only the connections watching that room, not every shard subscriber.
func roomChannel(roomID string) string {
	return "ep:ws:room:" + roomID
}

// Hub manages WS broadcast routing with sharded channels.
// D-029: cross-pod broadcast will use Pulsar (persistent://public/ws/broadcast),
// but M0 skeleton uses Redis Pub/Sub for local shard routing.
//
// D-149: added room-level broadcast for board game live play. Each room has
// its own Redis Pub/Sub channel; the subscribe loop (StartSubscribe) bridges
// Redis messages to in-memory subscriber channels.
type Hub struct {
	rdb    *redis.Client
	shards int
	mu     sync.RWMutex
	conns  map[string][]chan []byte // sessionID → subscriber channels (per-session)

	// D-149: room subscribers. roomID → subscriber channels.
	roomConns map[string][]chan []byte

	// D-149: subscription management — tracks which shard/room channels are
	// currently subscribed so we only open one Redis subscription per channel.
	subMu     sync.Mutex
	subscribed map[string]bool // channel → subscribed?
}

// New creates a Hub with the given Redis client and shard count.
func New(rdb *redis.Client, shards int) *Hub {
	if shards <= 0 {
		shards = 8
	}
	return &Hub{
		rdb:       rdb,
		shards:    shards,
		conns:     map[string][]chan []byte{},
		roomConns: map[string][]chan []byte{},
		subscribed: map[string]bool{},
	}
}

// Broadcast publishes a message to the shard channel for the given session.
func (h *Hub) Broadcast(ctx context.Context, sessionID string, payload []byte) error {
	if h.rdb == nil {
		return fmt.Errorf("no redis client")
	}
	return h.rdb.Publish(ctx, shardChannelFor(sessionID, h.shards), payload).Err()
}

// BroadcastToRoom publishes a message to all subscribers of a game room.
// D-149: used by board game live play to push move updates to both players
// and spectators. Returns an error if Redis is not configured.
func (h *Hub) BroadcastToRoom(ctx context.Context, roomID string, payload []byte) error {
	if h.rdb == nil {
		return fmt.Errorf("no redis client")
	}
	if roomID == "" {
		return fmt.Errorf("empty room id")
	}
	return h.rdb.Publish(ctx, roomChannel(roomID), payload).Err()
}

// Register subscribes to a session's shard channel and returns a receive channel.
func (h *Hub) Register(sessionID string) chan []byte {
	ch := make(chan []byte, 64)
	h.mu.Lock()
	h.conns[sessionID] = append(h.conns[sessionID], ch)
	h.mu.Unlock()
	return ch
}

// Unregister removes a subscriber for a session.
func (h *Hub) Unregister(sessionID string, ch chan []byte) {
	h.mu.Lock()
	defer h.mu.Unlock()
	conns := h.conns[sessionID]
	for i, c := range conns {
		if c == ch {
			h.conns[sessionID] = append(conns[:i], conns[i+1:]...)
			close(ch)
			break
		}
	}
	if len(h.conns[sessionID]) == 0 {
		delete(h.conns, sessionID)
	}
}

// RegisterRoom subscribes to a room's broadcast channel and returns a receive
// channel. D-149: called when a WS client joins a game room.
func (h *Hub) RegisterRoom(roomID string) chan []byte {
	ch := make(chan []byte, 64)
	h.mu.Lock()
	h.roomConns[roomID] = append(h.roomConns[roomID], ch)
	h.mu.Unlock()
	return ch
}

// UnregisterRoom removes a room subscriber. D-149.
func (h *Hub) UnregisterRoom(roomID string, ch chan []byte) {
	h.mu.Lock()
	defer h.mu.Unlock()
	conns := h.roomConns[roomID]
	for i, c := range conns {
		if c == ch {
			h.roomConns[roomID] = append(conns[:i], conns[i+1:]...)
			close(ch)
			break
		}
	}
	if len(h.roomConns[roomID]) == 0 {
		delete(h.roomConns, roomID)
	}
}

// DeliverToSession delivers a payload directly to all in-memory subscribers of
// a session (bypassing Redis). D-149: used by the subscribe loop to bridge
// Redis Pub/Sub messages to local subscriber channels.
func (h *Hub) DeliverToSession(sessionID string, payload []byte) {
	h.mu.RLock()
	conns := h.conns[sessionID]
	h.mu.RUnlock()
	for _, ch := range conns {
		select {
		case ch <- payload:
		default:
			// channel full, drop to avoid blocking the subscribe loop
		}
	}
}

// DeliverToRoom delivers a payload directly to all in-memory subscribers of
// a room (bypassing Redis). D-149: used by the subscribe loop.
func (h *Hub) DeliverToRoom(roomID string, payload []byte) {
	h.mu.RLock()
	conns := h.roomConns[roomID]
	h.mu.RUnlock()
	for _, ch := range conns {
		select {
		case ch <- payload:
		default:
		}
	}
}

// StartSubscribe starts a goroutine that subscribes to all shard channels
// and room channels on Redis, bridging received messages to in-memory
// subscriber channels. D-149: this is the cross-pod broadcast bridge.
//
// For shard channels, the payload is expected to be a JSON object with a
// "session_id" field so the hub knows which local subscribers to deliver to.
// For room channels, the payload is delivered to all room subscribers.
//
// The goroutine runs until ctx is cancelled. It is safe to call multiple
// times (each call starts an independent subscriber), but typically called
// once at startup.
func (h *Hub) StartSubscribe(ctx context.Context) {
	if h.rdb == nil {
		return
	}

	// Subscribe to all shard channels using pattern subscription.
	go h.subscribePattern(ctx, "ep:ws:shard:*", func(payload string) {
		// Shard messages are session-targeted. The payload format is:
		// {"session_id":"...","data":...}
		// We parse the session_id and deliver locally.
		sid := extractSessionID([]byte(payload))
		if sid != "" {
			h.DeliverToSession(sid, []byte(payload))
		}
	})

	// Subscribe to all room channels.
	go h.subscribePattern(ctx, "ep:ws:room:*", func(payload string) {
		// Room messages go to all subscribers of the room.
		// Extract room ID from the channel name in the message envelope.
		roomID := extractRoomID([]byte(payload))
		if roomID != "" {
			h.DeliverToRoom(roomID, []byte(payload))
		}
	})
}

// subscribePattern subscribes to a Redis key pattern and calls handler for
// each received message. Blocks until ctx is cancelled.
func (h *Hub) subscribePattern(ctx context.Context, pattern string, handler func(payload string)) {
	pubsub := h.rdb.PSubscribe(ctx, pattern)
	defer pubsub.Close()

	ch := pubsub.Channel()
	for {
		select {
		case <-ctx.Done():
			return
		case msg, ok := <-ch:
			if !ok {
				return
			}
			handler(msg.Payload)
		}
	}
}

// ShardChannel returns the shard channel name (exported for testing).
func (h *Hub) ShardChannel(sessionID string) string {
	return shardChannelFor(sessionID, h.shards)
}

// RoomChannel returns the room channel name (exported for testing). D-149.
func (h *Hub) RoomChannel(roomID string) string {
	return roomChannel(roomID)
}

// GetShards returns the shard count.
func (h *Hub) GetShards() int {
	return h.shards
}

// extractSessionID attempts to extract a "session_id" field from a JSON
// payload. Returns "" if not found. Uses a simple byte scan to avoid pulling
// in a JSON dependency in the hub package.
func extractSessionID(data []byte) string {
	return extractJSONStringField(data, "session_id")
}

// extractRoomID attempts to extract a "room_id" field from a JSON payload.
func extractRoomID(data []byte) string {
	return extractJSONStringField(data, "room_id")
}

// extractJSONStringField scans a JSON byte slice for a string field with the
// given key. This is a minimal parser that avoids pulling encoding/json into
// the hub package; it handles the common case of flat JSON envelopes used by
// the WS broadcast protocol. Returns "" if not found.
func extractJSONStringField(data []byte, key string) string {
	needle := []byte(`"` + key + `":"`)
	idx := indexOf(data, needle)
	if idx < 0 {
		return ""
	}
	start := idx + len(needle)
	end := start
	for end < len(data) && data[end] != '"' && data[end] != '\\' {
		end++
	}
	if end >= len(data) {
		return ""
	}
	return string(data[start:end])
}

func indexOf(haystack, needle []byte) int {
	if len(needle) == 0 || len(haystack) < len(needle) {
		return -1
	}
	for i := 0; i <= len(haystack)-len(needle); i++ {
		match := true
		for j := 0; j < len(needle); j++ {
			if haystack[i+j] != needle[j] {
				match = false
				break
			}
		}
		if match {
			return i
		}
	}
	return -1
}

// logSubscribeError logs a subscribe error without crashing the goroutine.
func logSubscribeError(channel string, err error) {
	log.Printf("hub: subscribe error on %s: %v", channel, err)
}
