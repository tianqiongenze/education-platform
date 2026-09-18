package ws

// ratelimit_test.go — D-220 anti-abuse unit tests: token bucket allowance,
// refill behavior, and the 64 KiB frame size cap.

import (
	"testing"
	"time"
)

func TestTokenBucket_BurstThenDepleted(t *testing.T) {
	tb := newTokenBucket(RateLimiterConfig{Rate: 10, Burst: 3})
	for i := 0; i < 3; i++ {
		if !tb.Allow() {
			t.Fatalf("token %d within burst should be allowed", i+1)
		}
	}
	if tb.Allow() {
		t.Fatal("bucket beyond burst must be depleted")
	}
}

func TestTokenBucket_RefillsOverTime(t *testing.T) {
	tb := newTokenBucket(RateLimiterConfig{Rate: 1000, Burst: 1})
	if !tb.Allow() {
		t.Fatal("first message should be allowed")
	}
	if tb.Allow() {
		t.Fatal("immediately after burst, must be limited")
	}
	// Rate 1000/s: 5ms elapsed ≈ 5 tokens.
	time.Sleep(6 * time.Millisecond)
	if !tb.Allow() {
		t.Fatal("after refill window a message should be allowed again")
	}
}

func TestMaxFrameSize_Constant(t *testing.T) {
	if MaxFrameSize != 64*1024 {
		t.Fatalf("MaxFrameSize must be 64KiB, got %d", MaxFrameSize)
	}
}
