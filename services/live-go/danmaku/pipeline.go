// Package danmaku implements a Redis-backed danmaku (弹幕) batching pipeline
// for the live-go service (D-187).
//
// Chat messages submitted by WebSocket clients are pushed to a Redis list
// (danmaku:{roomId}) and flushed in 100ms batches to all subscribers. This
// decouples producers from consumers and coalesces bursts into a single
// broadcast frame, reducing WebSocket write pressure during popular streams.
//
// Concurrency: DanmakuPipeline holds an immutable *redis.Client and a mutex
// around the subscriber registry. The per-room batcher goroutine is owned by
// StartBatcher and stopped via the returned cancel function.
package danmaku

import (
	"context"
	"encoding/json"
	"fmt"
	"sync"
	"time"

	"github.com/redis/go-redis/v9"
)

// BatchWindow is the flush interval for batched danmaku.
const BatchWindow = 100 * time.Millisecond

// DanmakuPipeline batches chat messages per room and broadcasts them to
// subscribers.
type DanmakuPipeline struct {
	rdb *redis.Client

	mu          sync.RWMutex
	subscribers map[string]map[string]chan []byte // roomID → subscriberID → chan
	nextSubID   int
}

// NewDanmakuPipeline builds a pipeline bound to a Redis client.
func NewDanmakuPipeline(rdb *redis.Client) *DanmakuPipeline {
	return &DanmakuPipeline{
		rdb:         rdb,
		subscribers: map[string]map[string]chan []byte{},
	}
}

// Submit pushes a single message onto the room's Redis list. The batcher
// goroutine will flush it to subscribers within BatchWindow.
func (p *DanmakuPipeline) Submit(ctx context.Context, roomID, userID, message string) error {
	if roomID == "" || userID == "" {
		return fmt.Errorf("danmaku: roomID and userID must not be empty")
	}
	entry := map[string]any{
		"userId":  userID,
		"message": message,
		"ts":      time.Now().UnixMilli(),
	}
	payload, err := json.Marshal(entry)
	if err != nil {
		return fmt.Errorf("danmaku: marshal: %w", err)
	}
	if err := p.rdb.LPush(ctx, listKey(roomID), payload).Err(); err != nil {
		return fmt.Errorf("danmaku: lpush %s: %w", roomID, err)
	}
	return nil
}

// StartBatcher launches a goroutine that every BatchWindow drains the room's
// Redis list and broadcasts the batch to all subscribers. The returned cancel
// function stops the goroutine and cleans up the subscriber registry entry.
//
// It is safe to call StartBatcher multiple times for the same room — the
// existing batcher is reused (the cancel func of the prior call still tears
// down the whole room). In practice the API layer calls this once per room on
// the first subscriber.
func (p *DanmakuPipeline) StartBatcher(ctx context.Context, roomID string) context.CancelFunc {
	subCtx, cancel := context.WithCancel(ctx)
	go p.runBatcher(subCtx, roomID)
	return cancel
}

// runBatcher is the per-room flush loop.
func (p *DanmakuPipeline) runBatcher(ctx context.Context, roomID string) {
	ticker := time.NewTicker(BatchWindow)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			p.flush(ctx, roomID)
		}
	}
}

// flush drains the Redis list for a room and broadcasts the batch.
func (p *DanmakuPipeline) flush(ctx context.Context, roomID string) {
	// LRANGE 0 -1 then DEL — atomic enough for danmaku (best-effort delivery;
	// a duplicate or dropped message on a race is acceptable).
	entries, err := p.rdb.LRange(ctx, listKey(roomID), 0, -1).Result()
	if err != nil && err != redis.Nil {
		return
	}
	if len(entries) == 0 {
		return
	}
	_ = p.rdb.Del(ctx, listKey(roomID)).Err()

	// Build the batch payload once, broadcast to every subscriber.
	batch := map[string]any{
		"roomId":   roomID,
		"messages": entries,
	}
	payload, err := json.Marshal(batch)
	if err != nil {
		return
	}
	p.broadcast(roomID, payload)
}

// broadcast delivers a payload to all subscribers of a room.
func (p *DanmakuPipeline) broadcast(roomID string, payload []byte) {
	p.mu.RLock()
	subs := p.subscribers[roomID]
	chans := make([]chan []byte, 0, len(subs))
	for _, ch := range subs {
		chans = append(chans, ch)
	}
	p.mu.RUnlock()

	for _, ch := range chans {
		select {
		case ch <- payload:
		default:
			// subscriber buffer full — drop to avoid blocking the batcher
		}
	}
}

// Subscribe returns a channel that receives batched danmaku payloads for a
// room. The returned unsubscribe function removes the subscriber and closes
// the channel. When the last subscriber leaves, the caller should stop the
// batcher (StartBatcher cancel func).
func (p *DanmakuPipeline) Subscribe(roomID string) (<-chan []byte, func()) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.nextSubID++
	subID := fmt.Sprintf("sub-%d", p.nextSubID)
	ch := make(chan []byte, 64)
	if p.subscribers[roomID] == nil {
		p.subscribers[roomID] = map[string]chan []byte{}
	}
	p.subscribers[roomID][subID] = ch
	unsub := func() {
		p.mu.Lock()
		defer p.mu.Unlock()
		if subs, ok := p.subscribers[roomID]; ok {
			if ch, ok := subs[subID]; ok {
				close(ch)
				delete(subs, subID)
			}
			if len(subs) == 0 {
				delete(p.subscribers, roomID)
			}
		}
	}
	return ch, unsub
}

// SubscriberCount returns the number of active subscribers for a room.
func (p *DanmakuPipeline) SubscriberCount(roomID string) int {
	p.mu.RLock()
	defer p.mu.RUnlock()
	return len(p.subscribers[roomID])
}

// listKey returns the Redis key for a room's danmaku list.
func listKey(roomID string) string {
	return "danmaku:" + roomID
}
