package ws

// ratelimit.go — per-connection message rate limiting (D-220).
//
// Pure-stdlib token bucket: no golang.org/x/time dependency (offline build).
// Each connection gets its own bucket; when the bucket is empty the
// connection is closed by readPump (fail-closed anti-abuse posture).

import (
	"sync"
	"time"
)

// RateLimiterConfig holds the per-connection message rate limits.
type RateLimiterConfig struct {
	// Rate is the sustained messages-per-second allowance.
	Rate float64
	// Burst is the maximum instantaneous allowance (bucket capacity).
	Burst int
}

// DefaultRateLimiterConfig returns the D-220 defaults: 10 msg/s sustained,
// burst of 20 (typical interactive move/join traffic stays well below).
func DefaultRateLimiterConfig() RateLimiterConfig {
	return RateLimiterConfig{Rate: 10, Burst: 20}
}

// MaxFrameSize is the server-side frame payload cap (64 KiB). Frames larger
// than this are rejected and the connection closed.
const MaxFrameSize = 64 * 1024

// tokenBucket is a minimal thread-safe token bucket.
type tokenBucket struct {
	mu       sync.Mutex
	tokens   float64
	burst    float64
	rate     float64 // tokens per second
	lastFill time.Time
}

func newTokenBucket(cfg RateLimiterConfig) *tokenBucket {
	return &tokenBucket{
		tokens:   float64(cfg.Burst),
		burst:    float64(cfg.Burst),
		rate:     cfg.Rate,
		lastFill: time.Now(),
	}
}

// Allow consumes one token, refilling by elapsed time first.
func (tb *tokenBucket) Allow() bool {
	tb.mu.Lock()
	defer tb.mu.Unlock()
	now := time.Now()
	elapsed := now.Sub(tb.lastFill).Seconds()
	tb.lastFill = now
	tb.tokens += elapsed * tb.rate
	if tb.tokens > tb.burst {
		tb.tokens = tb.burst
	}
	if tb.tokens < 1 {
		return false
	}
	tb.tokens--
	return true
}
