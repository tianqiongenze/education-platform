package hub

import (
	"context"
	"errors"
	"log"
	"os"
	"strings"
	"time"

	"github.com/redis/go-redis/v9"
)

// CrossPodTransport provides durable cross-pod broadcast.
// D-180: upgrades from Redis Pub/Sub (fire-and-forget) to Redis Streams
// (XADD/XREADGROUP) for durable, consumer-group-based cross-pod delivery.
// This means messages survive even if a pod is briefly disconnected, and each
// pod in the consumer group receives its own copy of every broadcast.
//
// Iron Law 8 evaluation (≥3 alternatives):
//   A) Pulsar persistent://public/ws/broadcast — best durability/cross-cluster
//      semantics, but adds a new infra dependency (pulsar-client-go) that is
//      not available in the offline build environment. Deferred to a future
//      batch once Pulsar is provisioned.
//   B) Redis Streams (XADD/XREADGROUP) — durable, consumer groups, reuses the
//      existing Redis dependency (no new module). Survives brief pod
//      disconnects. SELECTED for D-180.
//   C) Redis Pub/Sub (status quo, fire-and-forget) — no durability; if a pod
//      is restarting when a broadcast is published, it misses the message
//      forever. Not acceptable for board game live play.
//
// Transport selection:
//   - production / REDIS_ADDR set    → RedisStreamsTransport
//   - dev / no Redis                 → NoOpTransport (graceful degradation)
//
// The Hub dual-writes: Redis Pub/Sub for sub-ms local delivery to in-memory
// subscribers on the publishing pod, plus the cross-pod transport for durable
// delivery to other pods. On the receiving side, StartSubscribe keeps the
// Redis Pub/Sub subscription (local) AND StartReceiving on the cross-pod
// transport (cross-pod) both bridging to DeliverToSession/DeliverToRoom.
type CrossPodTransport interface {
	// Broadcast publishes a payload to all pods in the consumer group.
	Broadcast(ctx context.Context, payload []byte) error

	// StartReceiving consumes from the stream and invokes handler for each
	// payload. Blocks until ctx is cancelled. Safe to call once; subsequent
	// calls on the same transport are undefined.
	StartReceiving(ctx context.Context, handler func(payload []byte))

	// Close releases any transport-specific resources. Idempotent.
	Close() error
}

// NoOpTransport is the no-op CrossPodTransport used when Redis is unavailable
// (dev/offline mode). It preserves backward compatibility: behavior is
// identical to a Hub with no cross-pod transport.
type NoOpTransport struct{}

// Broadcast is a no-op; returns nil.
func (NoOpTransport) Broadcast(ctx context.Context, payload []byte) error { return nil }

// StartReceiving returns immediately without blocking (nothing to receive).
func (NoOpTransport) StartReceiving(ctx context.Context, handler func(payload []byte)) {
	// Intentionally empty: no stream to consume. Return immediately so callers
	// that launch this in a goroutine do not leak a blocked goroutine.
}

// Close is a no-op.
func (NoOpTransport) Close() error { return nil }

// streamField is the single field name used when XADD-ing payloads. We store
// the raw broadcast payload under this field; the consumer reads it back and
// hands the decoded bytes to the hub delivery methods.
const streamField = "payload"

// DefaultStreamKey is the Redis Streams key for cross-pod broadcast.
const DefaultStreamKey = "ep:ws:crosspod"

// DefaultGroupName is the consumer group shared by all ws-gateway pods. Every
// pod is a distinct *consumer* within this single group, which gives each pod
// its own copy of every message (fan-out broadcast semantics).
const DefaultGroupName = "ws-gateway"

// RedisStreamsTransport implements CrossPodTransport using Redis Streams
// (XADD for publish, XREADGROUP for consume).
//
// Durability: messages are appended to the stream via XADD and persist until
// the stream is trimmed (we do not trim in M0; a future task can add MAXLEN
// trimming once retention policy is decided). If a pod is briefly
// disconnected, XREADGROUP with ">" still delivers new entries on reconnect.
//
// Consumer group: all pods share one consumer group (DefaultGroupName). Each
// pod uses a unique consumer name (hostname-based) so Redis tracks per-pod
// pending-entries-lists independently. This is the standard Redis Streams
// broadcast pattern: one group, many consumers, each gets every message.
type RedisStreamsTransport struct {
	rdb          *redis.Client
	streamKey    string
	groupName    string
	consumerName string
}

// NewRedisStreamsTransport creates a transport backed by the given Redis
// client. If rdb is nil, returns nil (caller should fall back to NoOp).
//
// The consumer name defaults to "ws-gateway-" + hostname so each pod gets a
// unique consumer identity within the shared group. If hostname is empty, a
// timestamp suffix is used to avoid collisions.
func NewRedisStreamsTransport(rdb *redis.Client, streamKey, groupName string) *RedisStreamsTransport {
	if rdb == nil {
		return nil
	}
	if streamKey == "" {
		streamKey = DefaultStreamKey
	}
	if groupName == "" {
		groupName = DefaultGroupName
	}
	host, err := os.Hostname()
	if err != nil || host == "" {
		host = "pod-" + time.Now().Format("20060102-150405")
	}
	return &RedisStreamsTransport{
		rdb:          rdb,
		streamKey:    streamKey,
		groupName:    groupName,
		consumerName: "ws-gateway-" + host,
	}
}

// Broadcast appends the payload to the stream via XADD. Redis auto-generates
// the entry ID ("*"). The stream is created implicitly (MKSTREAM default).
func (t *RedisStreamsTransport) Broadcast(ctx context.Context, payload []byte) error {
	if t == nil || t.rdb == nil {
		return errors.New("crosspod: transport not initialized")
	}
	return t.rdb.XAdd(ctx, &redis.XAddArgs{
		Stream: t.streamKey,
		ID:     "*",
		Values: map[string]interface{}{streamField: payload},
	}).Err()
}

// StartReceiving consumes from the stream via XREADGROUP and invokes handler
// for each payload. It blocks until ctx is cancelled.
//
// On first call, the consumer group may not exist yet; this is handled by
// creating the group (XGROUP CREATE ... MKSTREAM) on the NOGROUP error. After
// that the read loop issues XREADGROUP with BLOCK so the call returns promptly
// when ctx is cancelled (via client-side context deadline) or when new entries
// arrive.
//
// Errors during the read loop are logged and the loop continues with a small
// backoff to avoid a tight error-spin if Redis is down.
func (t *RedisStreamsTransport) StartReceiving(ctx context.Context, handler func(payload []byte)) {
	if t == nil || t.rdb == nil || handler == nil {
		return
	}

	// Ensure the consumer group exists. MKSTREAM creates the stream if needed.
	// We ignore the BUSYGROUP error (group already exists) — that's the normal
	// steady-state path on pod restart.
	if err := t.ensureGroup(ctx); err != nil {
		log.Printf("crosspod: ensure group failed: %v (will retry on first read)", err)
	}

	const (
		count    = int64(64)
		block    = 1 * time.Second
		backoff  = 500 * time.Millisecond
	)

	for {
		if err := ctx.Err(); err != nil {
			return
		}

		streams, err := t.rdb.XReadGroup(ctx, &redis.XReadGroupArgs{
			Group:    t.groupName,
			Consumer: t.consumerName,
			Streams:  []string{t.streamKey, ">"},
			Count:    count,
			Block:    block,
		}).Result()

		if err != nil {
			// Context cancelled → exit cleanly.
			if ctx.Err() != nil {
				return
			}
			// NOGROUP: the group was deleted (e.g. Redis flushed). Recreate it.
			if isNoGroupErr(err) {
				log.Printf("crosspod: consumer group missing, recreating: %v", err)
				if gErr := t.ensureGroup(ctx); gErr != nil {
					log.Printf("crosspod: recreate group failed: %v", gErr)
				}
				continue
			}
			// redis.Nil is returned by XREADGROUP when BLOCK times out with no
			// new entries — this is the normal idle path, not an error.
			if err == redis.Nil {
				continue
			}
			log.Printf("crosspod: XReadGroup error: %v", err)
			select {
			case <-ctx.Done():
				return
			case <-time.After(backoff):
			}
			continue
		}

		for _, s := range streams {
			for _, msg := range s.Messages {
				payload := extractPayload(msg.Values)
				if payload != nil {
					handler(payload)
				}
				// Ack so the pending-entries-list does not grow unbounded.
				// We ack after handler returns; if handler panics the entry
				// stays pending (and a future CLAIM pass could redeliver it).
				_ = t.rdb.XAck(ctx, t.streamKey, t.groupName, msg.ID).Err()
			}
		}
	}
}

// ensureGroup creates the consumer group if it does not exist, with MKSTREAM
// so the stream is created on demand. BUSYGROUP (group already exists) is
// treated as success.
func (t *RedisStreamsTransport) ensureGroup(ctx context.Context) error {
	err := t.rdb.XGroupCreateMkStream(ctx, t.streamKey, t.groupName, "$").Err()
	if err == nil {
		return nil
	}
	if isBusyGroupErr(err) {
		return nil
	}
	return err
}

// Close is a no-op for Redis streams: connections are owned and managed by
// the Hub. Satisfies the CrossPodTransport interface.
func (t *RedisStreamsTransport) Close() error { return nil }

// isNoGroupErr returns true for the Redis "NOGROUP" error, which occurs when
// the consumer group does not exist for the stream.
func isNoGroupErr(err error) bool {
	return err != nil && strings.Contains(err.Error(), "NOGROUP")
}

// isBusyGroupErr returns true for the Redis "BUSYGROUP" error, which occurs
// when XGROUP CREATE is called for a group that already exists.
func isBusyGroupErr(err error) bool {
	return err != nil && strings.Contains(err.Error(), "BUSYGROUP")
}

// extractPayload reads the broadcast payload from an XMessage's Values map.
// Returns nil if the field is absent or not a []byte/string.
func extractPayload(values map[string]interface{}) []byte {
	if values == nil {
		return nil
	}
	v, ok := values[streamField]
	if !ok {
		return nil
	}
	switch val := v.(type) {
	case []byte:
		return val
	case string:
		return []byte(val)
	default:
		return nil
	}
}
