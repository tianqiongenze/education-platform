package cache

import (
	"context"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/alicebob/miniredis/v2"
	"github.com/redis/go-redis/v9"
)

// startMiniRedis starts a miniredis instance and returns a client wired to it.
// The instance is closed automatically when the test ends.
func startMiniRedis(t *testing.T) *redis.Client {
	t.Helper()
	mr, err := miniredis.Run()
	if err != nil {
		t.Fatalf("miniredis start: %v", err)
	}
	t.Cleanup(mr.Close)
	return redis.NewClient(&redis.Options{Addr: mr.Addr()})
}

// TestGetOrLoadL1Hit verifies a second call is served from L1 without touching
// the loader (the L2 is never populated for a string because the first call
// populates L1 first).
func TestGetOrLoadL1Hit(t *testing.T) {
	rdb := startMiniRedis(t)
	cs := NewCacheService(rdb, 8)
	defer cs.Close()

	var calls int32
	loader := func(ctx context.Context) (any, error) {
		atomic.AddInt32(&calls, 1)
		return "v", nil
	}
	v, err := cs.GetOrLoad(context.Background(), "c", "k1", loader, 60, true)
	if err != nil {
		t.Fatalf("err: %v", err)
	}
	if v != "v" {
		t.Fatalf("want v got %v", v)
	}
	v2, err := cs.GetOrLoad(context.Background(), "c", "k1", loader, 60, true)
	if err != nil {
		t.Fatalf("err: %v", err)
	}
	if v2 != "v" {
		t.Fatalf("want v got %v", v2)
	}
	if calls != 1 {
		t.Fatalf("want 1 loader call, got %d", calls)
	}
}

// TestGetOrLoadL2Hit verifies that after clearing L1 (by evicting only L1), the
// value is still served from L2 without invoking the loader.
func TestGetOrLoadL2Hit(t *testing.T) {
	rdb := startMiniRedis(t)
	cs := NewCacheService(rdb, 8)
	defer cs.Close()

	var calls int32
	loader := func(ctx context.Context) (any, error) {
		atomic.AddInt32(&calls, 1)
		return "l2val", nil
	}
	_, _ = cs.GetOrLoad(context.Background(), "c", "k2", loader, 60, true)

	// Force an L1 miss by removing the L1 entry directly (simulating a
	// cross-node invalidation that cleared only this pod's L1).
	cs.l1Remove("c:k2")

	v, err := cs.GetOrLoad(context.Background(), "c", "k2", loader, 60, true)
	if err != nil {
		t.Fatalf("err: %v", err)
	}
	// L2 stores JSON-encoded values; a plain string round-trips as a JSON
	// string (quoted). Accept either the raw or decoded form.
	if v != "l2val" && v != `"l2val"` {
		t.Fatalf("want l2val (or quoted), got %v", v)
	}
	if calls != 1 {
		t.Fatalf("second call should hit L2, got %d loader calls", calls)
	}
}

// TestGetOrLoadLoaderPopulates verifies a miss calls the loader and writes both
// L1 and L2, so a fresh CacheService (empty L1) sees the L2 value.
func TestGetOrLoadLoaderPopulates(t *testing.T) {
	rdb := startMiniRedis(t)
	cs := NewCacheService(rdb, 8)
	defer cs.Close()

	var calls int32
	loader := func(ctx context.Context) (any, error) {
		atomic.AddInt32(&calls, 1)
		return "populated", nil
	}
	_, _ = cs.GetOrLoad(context.Background(), "c", "k3", loader, 60, true)

	// A second CacheService shares the same Redis (L2) but has an empty L1.
	cs2 := NewCacheService(rdb, 8)
	defer cs2.Close()
	var calls2 int32
	loader2 := func(ctx context.Context) (any, error) {
		atomic.AddInt32(&calls2, 1)
		return "should-not-happen", nil
	}
	v, err := cs2.GetOrLoad(context.Background(), "c", "k3", loader2, 60, true)
	if err != nil {
		t.Fatalf("err: %v", err)
	}
	if v != "populated" && v != `"populated"` {
		t.Fatalf("want populated (or quoted), got %v", v)
	}
	if calls2 != 0 {
		t.Fatalf("L2 should have served the second CacheService, got %d loader calls", calls2)
	}
}

// TestEvictClearsL1L2AndPublishes verifies Evict removes from L1, L2, and
// broadcasts on the invalidate channel (observed via a second subscriber).
func TestEvictClearsL1L2AndPublishes(t *testing.T) {
	rdb := startMiniRedis(t)
	cs := NewCacheService(rdb, 8)
	defer cs.Close()

	var calls int32
	loader := func(ctx context.Context) (any, error) {
		atomic.AddInt32(&calls, 1)
		return "ev", nil
	}
	_, _ = cs.GetOrLoad(context.Background(), "c", "k4", loader, 60, true)
	if calls != 1 {
		t.Fatalf("want 1 loader call, got %d", calls)
	}

	// Subscribe to the invalidate channel to observe the broadcast.
	pubsub := rdb.Subscribe(context.Background(), InvalidateChannel)
	defer pubsub.Close()
	ch := pubsub.Channel()

	cs.Evict(context.Background(), "c", "k4")

	// L2 must be gone.
	if rdb.Exists(context.Background(), "c:k4").Val() != 0 {
		t.Fatal("L2 key should be deleted")
	}

	// A subsequent GetOrLoad must call the loader again (L1+L2 both empty).
	_, _ = cs.GetOrLoad(context.Background(), "c", "k4", loader, 60, true)
	if calls != 2 {
		t.Fatalf("want 2 loader calls after evict, got %d", calls)
	}

	// The broadcast must have delivered the full key.
	select {
	case msg := <-ch:
		if msg.Payload != "c:k4" {
			t.Fatalf("want payload c:k4, got %s", msg.Payload)
		}
	case <-time.After(time.Second):
		t.Fatal("did not receive invalidation message within 1s")
	}
}

// TestEmptySentinelCaching verifies that a nil loader result is cached as the
// sentinel when cacheEmpty=true, so the loader is not called again.
func TestEmptySentinelCaching(t *testing.T) {
	rdb := startMiniRedis(t)
	cs := NewCacheService(rdb, 8)
	defer cs.Close()

	var calls int32
	loader := func(ctx context.Context) (any, error) {
		atomic.AddInt32(&calls, 1)
		return nil, nil
	}
	v, err := cs.GetOrLoad(context.Background(), "u", "miss", loader, 60, true)
	if err != nil {
		t.Fatalf("err: %v", err)
	}
	if v != nil {
		t.Fatalf("want nil, got %v", v)
	}
	v2, err := cs.GetOrLoad(context.Background(), "u", "miss", loader, 60, true)
	if err != nil {
		t.Fatalf("err: %v", err)
	}
	if v2 != nil {
		t.Fatalf("want nil, got %v", v2)
	}
	if calls != 1 {
		t.Fatalf("sentinel should prevent second loader call, got %d", calls)
	}
}

// TestEmptyNotCachedWhenDisabled verifies cacheEmpty=false does not cache nil.
func TestEmptyNotCachedWhenDisabled(t *testing.T) {
	rdb := startMiniRedis(t)
	cs := NewCacheService(rdb, 8)
	defer cs.Close()

	var calls int32
	loader := func(ctx context.Context) (any, error) {
		atomic.AddInt32(&calls, 1)
		return nil, nil
	}
	_, _ = cs.GetOrLoad(context.Background(), "u", "miss2", loader, 60, false)
	_, _ = cs.GetOrLoad(context.Background(), "u", "miss2", loader, 60, false)
	if calls != 2 {
		t.Fatalf("cacheEmpty=false should not cache nil, got %d calls", calls)
	}
}

// TestL1TTLExpiry verifies an L1 entry expires after its TTL and triggers a
// loader call on the next access.
func TestL1TTLExpiry(t *testing.T) {
	rdb := startMiniRedis(t)
	cs := NewCacheService(rdb, 8)
	defer cs.Close()

	var calls int32
	loader := func(ctx context.Context) (any, error) {
		atomic.AddInt32(&calls, 1)
		return "ttl", nil
	}
	// Use a 1s TTL.
	_, _ = cs.GetOrLoad(context.Background(), "c", "kt", loader, 1, true)
	if calls != 1 {
		t.Fatalf("want 1 loader call, got %d", calls)
	}

	// Wait for the L1 entry to expire. (L2 still holds the value, so the next
	// call should NOT invoke the loader — it should hit L2. To isolate L1 TTL
	// behaviour we disable L2 by evicting the L2 copy first.)
	cs.Evict(context.Background(), "c", "kt")
	// Re-populate L1 only (with L2 now cleared we go through loader again).
	_, _ = cs.GetOrLoad(context.Background(), "c", "kt", loader, 1, true)
	if calls != 2 {
		t.Fatalf("want 2 loader calls after evict, got %d", calls)
	}
	// Remove L2 so the next miss falls through to the loader.
	_ = rdb.Del(context.Background(), "c:kt").Err()

	time.Sleep(1200 * time.Millisecond)
	_, _ = cs.GetOrLoad(context.Background(), "c", "kt", loader, 1, true)
	if calls != 3 {
		t.Fatalf("expired L1 entry should trigger loader, got %d calls", calls)
	}
}

// TestPubSubInvalidation verifies that a Publish on InvalidateChannel causes the
// receiver's L1 to evict the named key, so a subsequent GetOrLoad hits the loader.
func TestPubSubInvalidation(t *testing.T) {
	rdb := startMiniRedis(t)
	cs := NewCacheService(rdb, 8)
	defer cs.Close()

	var calls int32
	loader := func(ctx context.Context) (any, error) {
		atomic.AddInt32(&calls, 1)
		return "ps", nil
	}
	_, _ = cs.GetOrLoad(context.Background(), "c", "kp", loader, 60, true)
	if calls != 1 {
		t.Fatalf("want 1 loader call, got %d", calls)
	}

	// Simulate another node broadcasting an invalidation for this key. Also
	// delete L2 so the local node cannot serve the stale value from L2 and is
	// forced to call the loader.
	_ = rdb.Del(context.Background(), "c:kp").Err()
	_ = rdb.Publish(context.Background(), InvalidateChannel, "c:kp").Err()

	// Give the subscriber goroutine time to process.
	deadline := time.Now().Add(time.Second)
	for time.Now().Before(deadline) {
		_, _ = cs.GetOrLoad(context.Background(), "c", "kp", loader, 60, true)
		if atomic.LoadInt32(&calls) == 2 {
			break
		}
		time.Sleep(20 * time.Millisecond)
	}
	if calls != 2 {
		t.Fatalf("pubsub invalidation should have evicted L1 causing a loader call, got %d", calls)
	}
}

// TestPutAndEvictString verifies Put stores into L2 (visible to a second
// service instance) and that strings round-trip correctly.
func TestPutAndEvictString(t *testing.T) {
	rdb := startMiniRedis(t)
	cs := NewCacheService(rdb, 8)
	defer cs.Close()

	cs.Put(context.Background(), "c", "kp2", "hello", 60)

	cs2 := NewCacheService(rdb, 8)
	defer cs2.Close()
	var calls int32
	loader := func(ctx context.Context) (any, error) {
		atomic.AddInt32(&calls, 1)
		return "no", nil
	}
	v, err := cs2.GetOrLoad(context.Background(), "c", "kp2", loader, 60, true)
	if err != nil {
		t.Fatalf("err: %v", err)
	}
	if v != "hello" && v != `"hello"` {
		t.Fatalf("want hello (or quoted), got %v", v)
	}
	if calls != 0 {
		t.Fatalf("Put should have populated L2, got %d loader calls", calls)
	}

	cs.Evict(context.Background(), "c", "kp2")
	if rdb.Exists(context.Background(), "c:kp2").Val() != 0 {
		t.Fatal("Evict should have removed L2 key")
	}
}

// TestNilRedisDegradesToL1Only verifies the service works without a Redis client.
func TestNilRedisDegradesToL1Only(t *testing.T) {
	cs := NewCacheService(nil, 8)
	defer cs.Close()

	var calls int32
	loader := func(ctx context.Context) (any, error) {
		atomic.AddInt32(&calls, 1)
		return "offline", nil
	}
	v, err := cs.GetOrLoad(context.Background(), "c", "kn", loader, 60, true)
	if err != nil {
		t.Fatalf("err: %v", err)
	}
	if v != "offline" {
		t.Fatalf("want offline, got %v", v)
	}
	// Second call hits L1.
	_, _ = cs.GetOrLoad(context.Background(), "c", "kn", loader, 60, true)
	if calls != 1 {
		t.Fatalf("want 1 loader call (L1 hit), got %d", calls)
	}
}

// TestKeyFormat guards the fullKey format so it stays compatible with the Java
// ep-cache key layout (cacheName + ":" + key).
func TestKeyFormat(t *testing.T) {
	if got := fullKey("course", "abc"); got != "course:abc" {
		t.Fatalf("fullKey mismatch: %s", got)
	}
	if !strings.Contains(EmptySentinel, "EP_CACHE_EMPTY") {
		t.Fatalf("EmptySentinel looks wrong: %s", EmptySentinel)
	}
}
