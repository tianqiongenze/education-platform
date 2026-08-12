package hub

import (
	"context"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/alicebob/miniredis/v2"
	"github.com/redis/go-redis/v9"
)

// newTestRedis starts a miniredis and returns a connected client plus a
// cleanup func.
func newTestRedis(t *testing.T) (*redis.Client, func()) {
	t.Helper()
	mr, err := miniredis.Run()
	if err != nil {
		t.Fatalf("miniredis: %v", err)
	}
	rdb := redis.NewClient(&redis.Options{Addr: mr.Addr()})
	return rdb, func() {
		rdb.Close()
		mr.Close()
	}
}

// TestRedisStreamsBroadcast verifies that Broadcast appends the payload to the
// stream via XADD (the entry is durable in Redis).
func TestRedisStreamsBroadcast(t *testing.T) {
	rdb, cleanup := newTestRedis(t)
	defer cleanup()

	tr := NewRedisStreamsTransport(rdb, "ep:ws:crosspod", "ws-gateway")
	if tr == nil {
		t.Fatal("expected non-nil transport")
	}

	payload := []byte(`{"session_id":"s1","data":"hi"}`)
	if err := tr.Broadcast(context.Background(), payload); err != nil {
		t.Fatalf("Broadcast: %v", err)
	}

	// Verify the stream now has one entry with our payload field.
	vals, err := rdb.XRange(context.Background(), tr.streamKey, "-", "+").Result()
	if err != nil {
		t.Fatalf("XRange: %v", err)
	}
	if len(vals) != 1 {
		t.Fatalf("expected 1 stream entry, got %d", len(vals))
	}
	got := extractPayload(vals[0].Values)
	if string(got) != string(payload) {
		t.Fatalf("payload mismatch: want %q got %q", payload, got)
	}
}

// TestRedisStreamsBroadcastNilClient verifies NewRedisStreamsTransport returns
// nil when given a nil Redis client (graceful fallback signal).
func TestRedisStreamsBroadcastNilClient(t *testing.T) {
	if tr := NewRedisStreamsTransport(nil, "", ""); tr != nil {
		t.Fatalf("expected nil transport for nil redis client, got %T", tr)
	}
}

// TestRedisStreamsReceive verifies that messages added to the stream are
// received by StartReceiving and handed to the handler.
func TestRedisStreamsReceive(t *testing.T) {
	rdb, cleanup := newTestRedis(t)
	defer cleanup()

	tr := NewRedisStreamsTransport(rdb, "ep:ws:crosspod", "ws-gateway")
	if tr == nil {
		t.Fatal("expected non-nil transport")
	}

	var received int32
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	go tr.StartReceiving(ctx, func(payload []byte) {
		if strings.Contains(string(payload), "hello-stream") {
			atomic.AddInt32(&received, 1)
		}
	})

	// Give the receiver a moment to create the consumer group and enter the
	// XREADGROUP BLOCK loop.
	time.Sleep(150 * time.Millisecond)

	// Publish two messages.
	if err := tr.Broadcast(ctx, []byte("hello-stream-1")); err != nil {
		t.Fatalf("Broadcast 1: %v", err)
	}
	if err := tr.Broadcast(ctx, []byte("hello-stream-2")); err != nil {
		t.Fatalf("Broadcast 2: %v", err)
	}

	// Wait for both to arrive (or timeout).
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if atomic.LoadInt32(&received) >= 2 {
			break
		}
		time.Sleep(20 * time.Millisecond)
	}
	if got := atomic.LoadInt32(&received); got != 2 {
		t.Fatalf("expected 2 received, got %d", got)
	}
}

// TestRedisStreamsReceiveCreatesGroup verifies that StartReceiving creates the
// consumer group if it does not already exist (NOGROUP recovery path).
func TestRedisStreamsReceiveCreatesGroup(t *testing.T) {
	rdb, cleanup := newTestRedis(t)
	defer cleanup()

	tr := NewRedisStreamsTransport(rdb, "ep:ws:crosspod", "ws-gateway")

	// Pre-seed the group so the ensureGroup call hits BUSYGROUP on restart.
	if err := rdb.XGroupCreateMkStream(context.Background(), tr.streamKey, tr.groupName, "$").Err(); err != nil {
		t.Fatalf("pre-create group: %v", err)
	}

	var received int32
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go tr.StartReceiving(ctx, func(payload []byte) {
		atomic.AddInt32(&received, 1)
	})

	time.Sleep(100 * time.Millisecond)
	if err := tr.Broadcast(ctx, []byte("after-group")); err != nil {
		t.Fatalf("Broadcast: %v", err)
	}
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if atomic.LoadInt32(&received) >= 1 {
			break
		}
		time.Sleep(20 * time.Millisecond)
	}
	if got := atomic.LoadInt32(&received); got != 1 {
		t.Fatalf("expected 1 received after group recreate, got %d", got)
	}
}

// TestNoOpTransport verifies the no-op transport is inert: Broadcast returns
// nil and StartReceiving does not block.
func TestNoOpTransport(t *testing.T) {
	var n NoOpTransport
	if err := n.Broadcast(context.Background(), []byte("x")); err != nil {
		t.Fatalf("Broadcast should be no-op, got %v", err)
	}

	done := make(chan struct{})
	go func() {
		n.StartReceiving(context.Background(), func([]byte) {})
		close(done)
	}()
	select {
	case <-done:
		// good: returned immediately
	case <-time.After(500 * time.Millisecond):
		t.Fatal("StartReceiving should return immediately for NoOpTransport")
	}

	if err := n.Close(); err != nil {
		t.Fatalf("Close should be no-op, got %v", err)
	}
}

// TestHubDefaultCrossPodIsNoOp verifies that a freshly created Hub uses the
// NoOpTransport by default (backward compatible).
func TestHubDefaultCrossPodIsNoOp(t *testing.T) {
	h := New(nil, 8)
	if _, ok := h.crossPod.(NoOpTransport); !ok {
		t.Fatalf("expected default NoOpTransport, got %T", h.crossPod)
	}
}

// TestHubSetCrossPodTransport verifies SetCrossPodTransport injects the new
// transport and that passing nil resets to NoOp.
func TestHubSetCrossPodTransport(t *testing.T) {
	rdb, cleanup := newTestRedis(t)
	defer cleanup()

	h := New(rdb, 8)
	tr := NewRedisStreamsTransport(rdb, "", "")
	h.SetCrossPodTransport(tr)
	if h.crossPod != tr {
		t.Fatalf("expected crossPod to be the injected transport")
	}

	// nil resets to NoOp.
	h.SetCrossPodTransport(nil)
	if _, ok := h.crossPod.(NoOpTransport); !ok {
		t.Fatalf("expected NoOp after nil set, got %T", h.crossPod)
	}
}

// TestHubDualWrite verifies that Hub.Broadcast calls BOTH Redis Publish (for
// local listeners) AND crossPod.Broadcast (durable transport). We assert:
//   1. The Redis Pub/Sub subscriber receives the message (local path).
//   2. The injected transport recorded a Broadcast call (cross-pod path).
type recordingTransport struct {
	calls [][]byte
}

func (r *recordingTransport) Broadcast(ctx context.Context, payload []byte) error {
	r.calls = append(r.calls, payload)
	return nil
}
func (r *recordingTransport) StartReceiving(ctx context.Context, handler func(payload []byte)) {}
func (r *recordingTransport) Close() error { return nil }

func TestHubDualWrite(t *testing.T) {
	rdb, cleanup := newTestRedis(t)
	defer cleanup()

	h := New(rdb, 8)
	rec := &recordingTransport{}
	h.SetCrossPodTransport(rec)

	sid := "session-dual"
	ch := h.ShardChannel(sid)
	sub := rdb.Subscribe(context.Background(), ch)
	defer sub.Close()

	payload := []byte(`{"session_id":"session-dual","data":"x"}`)
	if err := h.Broadcast(context.Background(), sid, payload); err != nil {
		t.Fatal(err)
	}

	// 1. Redis Pub/Sub local delivery.
	msg, err := sub.ReceiveMessage(context.Background())
	if err != nil {
		t.Fatalf("ReceiveMessage: %v", err)
	}
	if msg.Payload != string(payload) {
		t.Fatalf("pubsub payload mismatch: want %q got %q", payload, msg.Payload)
	}

	// 2. Cross-pod transport was invoked with the same payload.
	if len(rec.calls) != 1 {
		t.Fatalf("expected 1 crossPod.Broadcast call, got %d", len(rec.calls))
	}
	if string(rec.calls[0]) != string(payload) {
		t.Fatalf("crosspod payload mismatch: want %q got %q", payload, rec.calls[0])
	}
}

// TestHubDualWriteRoom verifies BroadcastToRoom dual-writes to the cross-pod
// transport in addition to the room Pub/Sub channel.
func TestHubDualWriteRoom(t *testing.T) {
	rdb, cleanup := newTestRedis(t)
	defer cleanup()

	h := New(rdb, 8)
	rec := &recordingTransport{}
	h.SetCrossPodTransport(rec)

	roomID := "room-dual"
	ch := h.RoomChannel(roomID)
	sub := rdb.Subscribe(context.Background(), ch)
	defer sub.Close()

	payload := []byte(`{"type":"move","room_id":"room-dual"}`)
	if err := h.BroadcastToRoom(context.Background(), roomID, payload); err != nil {
		t.Fatal(err)
	}

	msg, err := sub.ReceiveMessage(context.Background())
	if err != nil {
		t.Fatalf("ReceiveMessage: %v", err)
	}
	if msg.Payload != string(payload) {
		t.Fatalf("pubsub payload mismatch: want %q got %q", payload, msg.Payload)
	}
	if len(rec.calls) != 1 {
		t.Fatalf("expected 1 crossPod.Broadcast call, got %d", len(rec.calls))
	}
}

// TestHubBroadcastCrossPodErrorNonFatal verifies that a failing cross-pod
// transport does NOT cause Broadcast to fail — the Redis Pub/Sub publish is
// still the authoritative return value (best-effort durability).
type errorTransport struct{}

func (errorTransport) Broadcast(ctx context.Context, payload []byte) error {
	return errCrossPodSimulated
}
func (errorTransport) StartReceiving(ctx context.Context, handler func(payload []byte)) {}
func (errorTransport) Close() error { return nil }

var errCrossPodSimulated = errSimulated("crosspod transport unavailable")

type errSimulated string

func (e errSimulated) Error() string { return string(e) }

func TestHubBroadcastCrossPodErrorNonFatal(t *testing.T) {
	rdb, cleanup := newTestRedis(t)
	defer cleanup()

	h := New(rdb, 8)
	h.SetCrossPodTransport(errorTransport{})

	sid := "session-err"
	ch := h.ShardChannel(sid)
	sub := rdb.Subscribe(context.Background(), ch)
	defer sub.Close()

	payload := []byte("hello")
	if err := h.Broadcast(context.Background(), sid, payload); err != nil {
		t.Fatalf("Broadcast should not fail when crossPod fails: %v", err)
	}
	// Redis Pub/Sub path still works.
	msg, err := sub.ReceiveMessage(context.Background())
	if err != nil {
		t.Fatalf("ReceiveMessage: %v", err)
	}
	if msg.Payload != string(payload) {
		t.Fatalf("pubsub payload mismatch: want %q got %q", payload, msg.Payload)
	}
}

