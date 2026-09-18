// Package cache provides a unified two-level (L1 LRU + L2 Redis) cache with
// cross-node invalidation via Redis Pub/Sub. It is the Go equivalent of the
// Java ep-cache SDK (services/ep-cache/src/main/java/com/ep/cache/CacheService.java)
// and is the mandatory shared implementation per Iron Law 10.3.
//
// Semantics mirror the Java CacheService:
//   - L1: in-process LRU with per-entry TTL
//   - L2: Redis (string values; EmptySentinel represents a cached nil)
//   - Pub/Sub: subscribers on channel "ep:cache:invalidate" evict their L1 on
//     message, so Evict on one node clears L1 on every node
//   - EmptySentinel = "__EP_CACHE_EMPTY__"
package cache

import (
	"container/list"
	"context"
	"encoding/json"
	"sync"
	"time"

	"github.com/redis/go-redis/v9"
)

// EmptySentinel is stored in L1/L2 to represent a cached nil/empty result,
// matching the Java ep-cache EMPTY_SENTINEL constant.
const EmptySentinel = "__EP_CACHE_EMPTY__"

// InvalidateChannel is the Redis Pub/Sub channel used to broadcast L1
// invalidation across nodes. It matches the Java ep-cache channel name.
const InvalidateChannel = "ep:cache:invalidate"

// DefaultL1Cap is the default L1 LRU capacity when 0 is passed to NewCacheService.
const DefaultL1Cap = 4096

// DefaultL2TTL is the default Redis TTL (seconds) applied when a non-zero
// ttlSec is not provided via GetOrLoad/Put. It matches the ws-gateway default.
const DefaultL2TTL = 300

// l1Entry is an L1 LRU entry with an absolute expiry time.
type l1Entry struct {
	key    string
	value  any
	expiry time.Time // zero == no expiry
}

// CacheService is a two-level cache with Redis-backed Pub/Sub invalidation.
// The zero value is not usable; construct with NewCacheService.
type CacheService struct {
	rdb *redis.Client

	l1Mu  sync.Mutex
	l1Cap int
	l1M   map[string]*list.Element
	l1L   *list.List

	cancel context.CancelFunc // stops the pubsub listener goroutine
	wg     sync.WaitGroup     // tracks the pubsub listener goroutine
}

// NewCacheService constructs a CacheService backed by the given Redis client
// (which may be nil to disable L2 + Pub/Sub, e.g. for offline/dev mode) and
// starts a background goroutine that listens on InvalidateChannel to evict L1
// entries invalidated by other nodes. l1Cap sets the L1 LRU size; 0 → DefaultL1Cap.
//
// The returned service must be closed via Close to stop the background goroutine.
func NewCacheService(rdb *redis.Client, l1Cap int) *CacheService {
	if l1Cap <= 0 {
		l1Cap = DefaultL1Cap
	}
	cs := &CacheService{
		rdb:    rdb,
		l1Cap:  l1Cap,
		l1M:    make(map[string]*list.Element),
		l1L:    list.New(),
	}
	if rdb != nil {
		ctx, cancel := context.WithCancel(context.Background())
		cs.cancel = cancel
		cs.wg.Add(1)
		go cs.listenInvalidations(ctx)
	}
	return cs
}

// Close stops the Pub/Sub listener goroutine. It is safe to call multiple times
// and on a service constructed without a Redis client.
func (cs *CacheService) Close() {
	if cs.cancel != nil {
		cs.cancel()
		cs.wg.Wait()
		cs.cancel = nil
	}
}

// RedisClient returns the underlying Redis client (may be nil). This is exposed
// so callers that need their own Redis reference (e.g. for a WebSocket Hub) can
// reuse the same client instead of allocating a second one.
func (cs *CacheService) RedisClient() *redis.Client { return cs.rdb }

// fullKey builds the canonical cache key: cacheName + ":" + key.
func fullKey(cacheName, key string) string {
	return cacheName + ":" + key
}

// GetOrLoad implements the L1 → L2 → loader fallback. On a miss it calls loader
// and populates L1 (and L2 when available). ttlSec is the TTL applied to L2
// writes (and to L1 entries; L1 entries also expire passively on read). When
// ttlSec <= 0, DefaultL2TTL is used. cacheEmpty controls whether a nil loader
// result is cached as EmptySentinel (L1 always; L2 with a 30s TTL, matching Java).
//
// Returns:
//   - the cached/loaded value (nil if a sentinel or nil loader result)
//   - any loader or Redis error (Redis errors degrade to L1-only, never fail the call)
func (cs *CacheService) GetOrLoad(
	ctx context.Context,
	cacheName, key string,
	loader func(ctx context.Context) (any, error),
	ttlSec int64,
	cacheEmpty bool,
) (any, error) {
	fk := fullKey(cacheName, key)

	// L1
	if v, ok := cs.l1Get(fk); ok {
		if v == EmptySentinel {
			return nil, nil
		}
		return v, nil
	}

	// L2
	if cs.rdb != nil {
		if raw, err := cs.rdb.Get(ctx, fk).Result(); err == nil {
			if raw == EmptySentinel {
				cs.l1Put(fk, EmptySentinel, 0)
				return nil, nil
			}
			// L2 stores strings; decode JSON to recover structured values when
			// possible, otherwise keep the raw string.
			var decoded any
			if json.Unmarshal([]byte(raw), &decoded) == nil {
				cs.l1Put(fk, decoded, 0)
				return decoded, nil
			}
			cs.l1Put(fk, raw, 0)
			return raw, nil
		}
	}

	// loader
	v, err := loader(ctx)
	if err != nil {
		return nil, err
	}

	ttl := ttlSec
	if ttl <= 0 {
		ttl = DefaultL2TTL
	}

	if v != nil {
		cs.l1Put(fk, v, ttl)
		if cs.rdb != nil {
			cs.l2Set(ctx, fk, v, time.Duration(ttl)*time.Second)
		}
		return v, nil
	}

	// nil loader result
	if cacheEmpty {
		if cs.rdb != nil {
			cs.l2Set(ctx, fk, EmptySentinel, 30*time.Second)
		}
		cs.l1Put(fk, EmptySentinel, 30)
	}
	return nil, nil
}

// Put writes value to L1 and L2 under cacheName:key with the given TTL. A nil
// value is stored as EmptySentinel. ttlSec <= 0 → DefaultL2TTL. No Pub/Sub
// message is published (use Evict to invalidate across nodes).
func (cs *CacheService) Put(ctx context.Context, cacheName, key string, value any, ttlSec int64) {
	fk := fullKey(cacheName, key)
	ttl := ttlSec
	if ttl <= 0 {
		ttl = DefaultL2TTL
	}
	stored := value
	if stored == nil {
		stored = EmptySentinel
	}
	cs.l1Put(fk, stored, ttl)
	if cs.rdb != nil {
		cs.l2Set(ctx, fk, stored, time.Duration(ttl)*time.Second)
	}
}

// Evict deletes cacheName:key from L1 and L2 and broadcasts an invalidation
// message on InvalidateChannel so other nodes clear their L1. This is the
// correctness fix: the previous ws-gateway Evict only cleared the local L1+L2
// and never broadcast, leaving stale L1 entries on peer pods.
//
// D-216 cross-language wire contract: the payload is JSON
// {"cacheName":"...","key":"..."} — identical to the Java ep-cache
// RedisCacheBroadcast (InvalidateMsg). The previous raw "cache:key" string was
// invisible to Java/Python subscribers and vice versa, so invalidation never
// propagated across languages.
func (cs *CacheService) Evict(ctx context.Context, cacheName, key string) {
	fk := fullKey(cacheName, key)
	cs.l1Remove(fk)
	if cs.rdb != nil {
		cs.rdb.Del(ctx, fk)
		// Broadcast the cross-language JSON contract (see D-216 note above).
		payload, err := json.Marshal(invalidateMsg{CacheName: cacheName, Key: key})
		if err != nil {
			// marshal of two strings cannot fail in practice; fall back to raw
			payload = []byte(fk)
		}
		_ = cs.rdb.Publish(ctx, InvalidateChannel, payload).Err()
	}
}

// invalidateMsg mirrors the Java ep-cache InvalidateMsg wire format.
type invalidateMsg struct {
	CacheName string `json:"cacheName"`
	Key       string `json:"key"`
}

// --- L1 (LRU + per-entry TTL) ---

func (cs *CacheService) l1Get(k string) (any, bool) {
	cs.l1Mu.Lock()
	defer cs.l1Mu.Unlock()
	e, ok := cs.l1M[k]
	if !ok {
		return nil, false
	}
	ent := e.Value.(l1Entry)
	if !ent.expiry.IsZero() && time.Now().After(ent.expiry) {
		// expired: drop and report miss
		cs.l1L.Remove(e)
		delete(cs.l1M, k)
		return nil, false
	}
	cs.l1L.MoveToFront(e)
	return ent.value, true
}

func (cs *CacheService) l1Put(k string, v any, ttlSec int64) {
	cs.l1Mu.Lock()
	defer cs.l1Mu.Unlock()
	var expiry time.Time
	if ttlSec > 0 {
		expiry = time.Now().Add(time.Duration(ttlSec) * time.Second)
	}
	if e, ok := cs.l1M[k]; ok {
		e.Value = l1Entry{key: k, value: v, expiry: expiry}
		cs.l1L.MoveToFront(e)
		return
	}
	cs.l1M[k] = cs.l1L.PushFront(l1Entry{key: k, value: v, expiry: expiry})
	for cs.l1L.Len() > cs.l1Cap {
		back := cs.l1L.Back()
		cs.l1L.Remove(back)
		delete(cs.l1M, back.Value.(l1Entry).key)
	}
}

func (cs *CacheService) l1Remove(k string) {
	cs.l1Mu.Lock()
	defer cs.l1Mu.Unlock()
	if e, ok := cs.l1M[k]; ok {
		cs.l1L.Remove(e)
		delete(cs.l1M, k)
	}
}

// --- L2 (Redis) ---

// l2Set serializes value to a string for Redis. Strings are stored as-is;
// other types are JSON-encoded. This keeps round-tripping simple while
// preserving the EmptySentinel string exactly.
func (cs *CacheService) l2Set(ctx context.Context, fk string, value any, ttl time.Duration) {
	var s string
	switch v := value.(type) {
	case string:
		s = v
	default:
		b, err := json.Marshal(v)
		if err != nil {
			return
		}
		s = string(b)
	}
	_ = cs.rdb.Set(ctx, fk, s, ttl).Err()
}

// --- Pub/Sub listener ---

// listenInvalidations subscribes to InvalidateChannel and evicts received keys
// from the local L1. It runs until ctx is cancelled or the subscription closes.
//
// D-216: accepts the unified JSON wire format {"cacheName","key"} (Java/Go/
// Python contract) and, during the rolling-upgrade window, the legacy raw
// "cache:key" string and the legacy Python {"cache","key"} field name.
func (cs *CacheService) listenInvalidations(ctx context.Context) {
	defer cs.wg.Done()
	pubsub := cs.rdb.Subscribe(ctx, InvalidateChannel)
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
			if msg != nil {
				if fk := parseInvalidatePayload(msg.Payload); fk != "" {
					cs.l1Remove(fk)
				}
			}
		}
	}
}

// parseInvalidatePayload extracts the full cache key from a broadcast payload,
// accepting the D-216 JSON contract plus both legacy formats.
func parseInvalidatePayload(payload string) string {
	var m struct {
		CacheName string `json:"cacheName"`
		Cache     string `json:"cache"` // legacy Python field
		Key       string `json:"key"`
	}
	if err := json.Unmarshal([]byte(payload), &m); err == nil {
		name := m.CacheName
		if name == "" {
			name = m.Cache
		}
		if name != "" && m.Key != "" {
			return fullKey(name, m.Key)
		}
		return ""
	}
	// legacy Go raw "cache:key" — treat as an already-composed full key
	if payload != "" {
		return payload
	}
	return ""
}
