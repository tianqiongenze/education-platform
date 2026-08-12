// Package srs implements SRS (Simple Realtime Server) origin cluster routing
// and WHIP/WHEP proxying for the live-go service (D-187).
//
// origin.go manages stream-to-origin ownership via Redis:
//   - ClaimStream: atomic SETNX stream:{roomId} = originNode (TTL 24h)
//   - GetOrigin:   GET stream:{roomId}
//   - ReleaseStream: DEL stream:{roomId}
//   - SelectOrigin: least-loaded origin from SRS_ORIGINS
//
// Concurrency: OriginRouter holds only an immutable *redis.Client (safe for
// concurrent use). All per-room state lives in Redis, so multiple live-go
// replicas can claim/release streams consistently.
package srs

import (
	"context"
	"errors"
	"fmt"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/redis/go-redis/v9"
)

// streamTTL is the ownership lease for a stream→origin binding. 24h matches
// the longest allowed live session so a crashed origin's binding expires and
// the stream can be re-claimed by a healthy node.
const streamTTL = 24 * time.Hour

// ErrNoOrigins is returned when SRS_ORIGINS is not configured.
var ErrNoOrigins = errors.New("srs: no origin nodes configured (SRS_ORIGINS empty)")

// ErrAlreadyClaimed is returned by ClaimStream when another origin already owns
// the stream.
var ErrAlreadyClaimed = errors.New("srs: stream already claimed by another origin")

// OriginRouter resolves which SRS origin node owns a given stream and selects
// the least-loaded origin for new streams.
type OriginRouter struct {
	rdb     *redis.Client
	origins []string // host:port list, immutable after construction
}

// NewOriginRouter builds an OriginRouter from a Redis client and the
// SRS_ORIGINS env var (comma-separated host:port). Returns ErrNoOrigins when
// the env var is empty — callers should treat this as a configuration error.
func NewOriginRouter(rdb *redis.Client) (*OriginRouter, error) {
	raw := os.Getenv("SRS_ORIGINS")
	if raw == "" {
		return nil, ErrNoOrigins
	}
	parts := strings.Split(raw, ",")
	origins := make([]string, 0, len(parts))
	for _, p := range parts {
		p = strings.TrimSpace(p)
		if p != "" {
			origins = append(origins, p)
		}
	}
	if len(origins) == 0 {
		return nil, ErrNoOrigins
	}
	return &OriginRouter{rdb: rdb, origins: origins}, nil
}

// NewOriginRouterWithOrigins is the test-friendly constructor that injects the
// origin list directly (avoids mutating the process env in unit tests).
func NewOriginRouterWithOrigins(rdb *redis.Client, origins []string) (*OriginRouter, error) {
	if len(origins) == 0 {
		return nil, ErrNoOrigins
	}
	cp := make([]string, len(origins))
	copy(cp, origins)
	return &OriginRouter{rdb: rdb, origins: cp}, nil
}

// streamKey returns the Redis key for a stream→origin binding.
func streamKey(roomID string) string {
	return "stream:" + roomID
}

// loadKey returns the Redis key for an origin's current load counter.
func loadKey(node string) string {
	return "origin:load:" + node
}

// ClaimStream atomically claims ownership of a stream for an origin node.
// Uses SET NX so only the first caller wins; subsequent claims return
// ErrAlreadyClaimed. The binding expires after streamTTL so a dead origin does
// not pin the stream forever.
//
// Returns (claimed=true, nil) on success, (false, ErrAlreadyClaimed) when
// another node owns it, and (false, err) on Redis errors.
func (o *OriginRouter) ClaimStream(ctx context.Context, roomID, originNode string) (bool, error) {
	if roomID == "" || originNode == "" {
		return false, fmt.Errorf("srs: roomID and originNode must not be empty")
	}
	ok, err := o.rdb.SetNX(ctx, streamKey(roomID), originNode, streamTTL).Result()
	if err != nil {
		return false, fmt.Errorf("srs: claim stream %s: %w", roomID, err)
	}
	if !ok {
		// Surface which node currently owns it for diagnostics.
		owner, _ := o.rdb.Get(ctx, streamKey(roomID)).Result()
		return false, fmt.Errorf("%w: owner=%s", ErrAlreadyClaimed, owner)
	}
	// Bump the origin's load so SelectOrigin reflects the new stream.
	if err := o.rdb.Incr(ctx, loadKey(originNode)).Err(); err != nil {
		// Non-fatal: load tracking is best-effort. The claim itself succeeded.
		_ = err
	}
	return true, nil
}

// GetOrigin returns the origin node that owns the stream, or "" if unclaimed.
func (o *OriginRouter) GetOrigin(ctx context.Context, roomID string) (string, error) {
	v, err := o.rdb.Get(ctx, streamKey(roomID)).Result()
	if err == redis.Nil {
		return "", nil
	}
	if err != nil {
		return "", fmt.Errorf("srs: get origin for %s: %w", roomID, err)
	}
	return v, nil
}

// ReleaseStream deletes the stream→origin binding and decrements the origin's
// load counter. Safe to call on an already-released stream (DEL is idempotent).
func (o *OriginRouter) ReleaseStream(ctx context.Context, roomID string) error {
	origin, err := o.rdb.Get(ctx, streamKey(roomID)).Result()
	if err != nil && err != redis.Nil {
		return fmt.Errorf("srs: release stream %s: %w", roomID, err)
	}
	if err := o.rdb.Del(ctx, streamKey(roomID)).Err(); err != nil {
		return fmt.Errorf("srs: del stream %s: %w", roomID, err)
	}
	if origin != "" {
		// Decrement load, floored at 0.
		n, _ := o.rdb.Decr(ctx, loadKey(origin)).Result()
		if n < 0 {
			_ = o.rdb.Set(ctx, loadKey(origin), 0, 0).Err()
		}
	}
	return nil
}

// SelectOrigin picks the least-loaded origin node. It reads the current load
// counter for every configured origin and returns the one with the lowest
// count; ties are broken by configuration order (deterministic).
//
// Returns ErrNoOrigins if no origins are configured.
func (o *OriginRouter) SelectOrigin(ctx context.Context) (string, error) {
	if len(o.origins) == 0 {
		return "", ErrNoOrigins
	}
	bestNode := o.origins[0]
	bestLoad := int64(1<<63 - 1) // max int64
	for _, node := range o.origins {
		v, err := o.rdb.Get(ctx, loadKey(node)).Result()
		var load int64
		if err == nil {
			load, _ = strconv.ParseInt(v, 10, 64)
		} else if err != redis.Nil {
			// Treat Redis errors as "unknown load = skip" so a transient
			// failure on one node does not abort selection.
			load = bestLoad // exclude from selection
		}
		if load < bestLoad {
			bestLoad = load
			bestNode = node
		}
	}
	return bestNode, nil
}

// Origins returns a copy of the configured origin list (for diagnostics).
func (o *OriginRouter) Origins() []string {
	out := make([]string, len(o.origins))
	copy(out, o.origins)
	return out
}
