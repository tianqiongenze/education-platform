package cache

import (
	"container/list"
	"context"
	"fmt"
	"os"
	"strconv"
	"sync"
	"time"

	"github.com/redis/go-redis/v9"
)

const EmptySentinel = "__EP_CACHE_EMPTY__"

type entry struct {
	k string
	v any
}

type l1Cache struct {
	mu  sync.Mutex
	cap int
	m   map[string]*list.Element
	l   *list.List
}

var l1 = newL1(4096)
var rdbOnce sync.Once
var rdb *redis.Client

func newL1(cap int) *l1Cache {
	return &l1Cache{cap: cap, m: map[string]*list.Element{}, l: list.New()}
}

func (c *l1Cache) get(k string) (any, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if e, ok := c.m[k]; ok {
		c.l.MoveToFront(e)
		return e.Value.(entry).v, true
	}
	return nil, false
}

func (c *l1Cache) put(k string, v any) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if e, ok := c.m[k]; ok {
		e.Value = entry{k, v}
		c.l.MoveToFront(e)
		return
	}
	c.m[k] = c.l.PushFront(entry{k, v})
	for c.l.Len() > c.cap {
		e := c.l.Back()
		c.l.Remove(e)
		delete(c.m, e.Value.(entry).k)
	}
}

func (c *l1Cache) remove(k string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if e, ok := c.m[k]; ok {
		c.l.Remove(e)
		delete(c.m, k)
	}
}

func keyFor(name string, parts []any) string {
	k := name
	for _, p := range parts {
		k += ":" + fmt.Sprint(p)
	}
	return k
}

func getClient() *redis.Client {
	rdbOnce.Do(func() {
		host := os.Getenv("REDIS_HOST")
		if host == "" {
			host = "redis-cluster"
		}
		port := os.Getenv("REDIS_PORT")
		if port == "" {
			port = "6379"
		}
		rdb = redis.NewClient(&redis.Options{Addr: host + ":" + port})
	})
	return rdb
}

// SetClientForTest injects a mock redis client for testing.
func SetClientForTest(c *redis.Client) {
	rdb = c
	rdbOnce = sync.Once{} // reset so getClient doesn't override
}

// GetRedisClientForHub returns a Redis client suitable for the Hub to use
// for Pub/Sub. D-149: the Hub needs its own client reference (not the
// singleton) so it can be nil-checked independently. This returns the same
// underlying client as getClient() if one has been initialized, or nil if
// Redis is not available (dev/offline mode).
func GetRedisClientForHub() *redis.Client {
	return getClient()
}

// GetOrLoad: L1 LRU → L2 Redis → loader. cacheEmpty controls nil sentinel caching.
func GetOrLoad(ctx context.Context, name string, parts []any, loader func(context.Context) (any, error), cacheEmpty bool) (any, error) {
	k := keyFor(name, parts)
	if v, ok := l1.get(k); ok {
		if v == EmptySentinel {
			return nil, nil
		}
		return v, nil
	}
	c := getClient()
	if c != nil {
		if raw, err := c.Get(ctx, k).Result(); err == nil {
			if raw == EmptySentinel {
				l1.put(k, EmptySentinel)
				return nil, nil
			}
			l1.put(k, raw)
			return raw, nil
		}
	}
	v, err := loader(ctx)
	if err != nil {
		return nil, err
	}
	if v != nil {
		l1.put(k, v)
		if c != nil {
			ttl, _ := strconv.Atoi(getEnvDefault("CACHE_L2_TTL", "300"))
			c.Set(ctx, k, v, time.Duration(ttl)*time.Second)
		}
	}
	if v == nil && cacheEmpty {
		if c != nil {
			c.Set(ctx, k, EmptySentinel, 30*time.Second)
		}
		l1.put(k, EmptySentinel)
	}
	return v, nil
}

// Evict removes from L1 + L2 + broadcasts invalidation.
func Evict(ctx context.Context, name string, parts ...any) {
	k := keyFor(name, parts)
	c := getClient()
	if c != nil {
		c.Del(ctx, k)
	}
	l1.remove(k)
}

func getEnvDefault(k, d string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return d
}
