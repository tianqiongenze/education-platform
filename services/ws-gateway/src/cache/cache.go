// Package cache is the ws-gateway local facade over the shared
// github.com/education-platform/ep-common-go/cache CacheService.
//
// D-10.3 (Iron Law: shared SDK mandatory use): the hand-written LRU+Redis
// implementation that previously lived here has been removed. All cache
// behaviour (L1 LRU+TTL, L2 Redis, Pub/Sub invalidation on
// "ep:cache:invalidate") is provided by ep-common-go/cache and merely wrapped
// here so existing ws-gateway callers keep their signatures.
//
// Bug fix carried over from the migration: Evict now broadcasts an invalidation
// message on "ep:cache:invalidate" via the shared CacheService, so peer pods
// evict their L1 entries. The previous local implementation only cleared the
// local L1+L2 and never broadcast, which left stale L1 entries on other pods.
package cache

import (
	"context"
	"fmt"
	"os"
	"strconv"
	"sync"

	commoncache "github.com/education-platform/ep-common-go/cache"
	"github.com/redis/go-redis/v9"
)

// EmptySentinel mirrors the shared cache's sentinel for backward compatibility.
const EmptySentinel = commoncache.EmptySentinel

var (
	mu   sync.Mutex
	svc  *commoncache.CacheService
	rdb  *redis.Client
	once sync.Once
)

// keyFor builds the canonical cache key from a name and variadic parts, exactly
// as the previous implementation did ("name:p1:p2:...").
func keyFor(name string, parts []any) string {
	k := name
	for _, p := range parts {
		k += ":" + fmt.Sprint(p)
	}
	return k
}

// getService returns the singleton CacheService, lazily constructing it from the
// environment on first use. When no Redis client has been injected (via
// SetClientForTest), a real client is built from REDIS_HOST/REDIS_PORT.
func getService() *commoncache.CacheService {
	mu.Lock()
	defer mu.Unlock()
	if svc != nil {
		return svc
	}
	once.Do(func() {
		if rdb == nil {
			rdb = newClientFromEnv()
		}
	})
	svc = commoncache.NewCacheService(rdb, 0)
	return svc
}

// newClientFromEnv builds a Redis client from REDIS_HOST/REDIS_PORT, defaulting
// to redis-cluster:6379.
func newClientFromEnv() *redis.Client {
	host := os.Getenv("REDIS_HOST")
	if host == "" {
		host = "redis-cluster"
	}
	port := os.Getenv("REDIS_PORT")
	if port == "" {
		port = "6379"
	}
	return redis.NewClient(&redis.Options{Addr: host + ":" + port})
}

// SetClientForTest injects a mock Redis client and resets the singleton
// CacheService so the next call to GetOrLoad/Evict rebuilds it. Pass nil to
// force offline (L1-only) mode. This preserves the prior test-only API.
func SetClientForTest(c *redis.Client) {
	mu.Lock()
	defer mu.Unlock()
	if svc != nil {
		svc.Close()
		svc = nil
	}
	rdb = c
	once = sync.Once{}
}

// GetRedisClientForHub returns the Redis client backing the cache singleton, or
// nil when Redis is unavailable (dev/offline mode). The Hub reuses this client
// for its own Pub/Sub rather than allocating a second one (D-149).
func GetRedisClientForHub() *redis.Client {
	// Ensure the singleton (and its client) is initialized.
	s := getService()
	return s.RedisClient()
}

// GetOrLoad is the ws-gateway cache facade: L1 LRU → L2 Redis → loader.
// parts are joined into the cache key as "name:p1:p2:...". cacheEmpty controls
// nil-sentinel caching. The shared CacheService handles TTL, L2, and Pub/Sub.
func GetOrLoad(
	ctx context.Context,
	name string,
	parts []any,
	loader func(context.Context) (any, error),
	cacheEmpty bool,
) (any, error) {
	key := keyFor(name, parts)
	ttl, _ := strconv.ParseInt(getEnvDefault("CACHE_L2_TTL", "300"), 10, 64)
	if ttl <= 0 {
		ttl = 300
	}
	return getService().GetOrLoad(ctx, name, key, loader, ttl, cacheEmpty)
}

// Evict removes name:parts... from L1+L2 and broadcasts an invalidation on
// "ep:cache:invalidate" so peer pods clear their L1. This is the correctness
// fix: the old implementation did not broadcast.
func Evict(ctx context.Context, name string, parts ...any) {
	key := keyFor(name, parts)
	getService().Evict(ctx, name, key)
}

func getEnvDefault(k, d string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return d
}
