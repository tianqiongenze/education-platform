package cache

import (
	"context"
	"testing"

	"github.com/alicebob/miniredis/v2"
	"github.com/redis/go-redis/v9"
)

func startMiniRedis(t *testing.T) *redis.Client {
	t.Helper()
	mr, err := miniredis.Run()
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(mr.Close)
	return redis.NewClient(&redis.Options{Addr: mr.Addr()})
}

func TestGetOrLoadMissCallsLoader(t *testing.T) {
	rdb := startMiniRedis(t)
	SetClientForTest(rdb)
	calls := 0
	v, err := GetOrLoad(context.Background(), "course", []any{"c1"},
		func(ctx context.Context) (any, error) { calls++; return "v", nil }, true)
	if err != nil {
		t.Fatal(err)
	}
	if v != "v" || calls != 1 {
		t.Fatalf("want v/1 got %v/%d", v, calls)
	}
}

func TestGetOrLoadSecondHitsL2(t *testing.T) {
	rdb := startMiniRedis(t)
	SetClientForTest(rdb)
	calls := 0
	_, _ = GetOrLoad(context.Background(), "course", []any{"c2"},
		func(ctx context.Context) (any, error) { calls++; return "v", nil }, true)
	_, _ = GetOrLoad(context.Background(), "course", []any{"c2"},
		func(ctx context.Context) (any, error) { calls++; return "v", nil }, true)
	if calls != 1 {
		t.Fatalf("second should hit L2, got %d calls", calls)
	}
}

func TestGetOrLoadEmptySentinel(t *testing.T) {
	rdb := startMiniRedis(t)
	SetClientForTest(rdb)
	calls := 0
	v, _ := GetOrLoad(context.Background(), "user", []any{"miss"},
		func(ctx context.Context) (any, error) { calls++; return nil, nil }, true)
	if v != nil {
		t.Fatalf("want nil got %v", v)
	}
	// second call should hit sentinel, not loader
	v2, _ := GetOrLoad(context.Background(), "user", []any{"miss"},
		func(ctx context.Context) (any, error) { calls++; return nil, nil }, true)
	if v2 != nil {
		t.Fatalf("want nil got %v", v2)
	}
	if calls != 1 {
		t.Fatalf("sentinel should prevent second loader call, got %d", calls)
	}
}

func TestGetOrLoadEmptyNotCachedWhenDisabled(t *testing.T) {
	rdb := startMiniRedis(t)
	SetClientForTest(rdb)
	calls := 0
	_, _ = GetOrLoad(context.Background(), "user", []any{"miss2"},
		func(ctx context.Context) (any, error) { calls++; return nil, nil }, false)
	_, _ = GetOrLoad(context.Background(), "user", []any{"miss2"},
		func(ctx context.Context) (any, error) { calls++; return nil, nil }, false)
	if calls != 2 {
		t.Fatalf("cacheEmpty=false should not cache, got %d calls", calls)
	}
}

func TestEvictRemovesFromCache(t *testing.T) {
	rdb := startMiniRedis(t)
	SetClientForTest(rdb)
	calls := 0
	_, _ = GetOrLoad(context.Background(), "test", []any{"e1"},
		func(ctx context.Context) (any, error) { calls++; return "val", nil }, true)
	Evict(context.Background(), "test", "e1")
	_, _ = GetOrLoad(context.Background(), "test", []any{"e1"},
		func(ctx context.Context) (any, error) { calls++; return "val", nil }, true)
	if calls != 2 {
		t.Fatalf("evict should cause second loader call, got %d calls", calls)
	}
}
