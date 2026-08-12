package hub

import (
	"context"
	"testing"

	"github.com/alicebob/miniredis/v2"
	"github.com/redis/go-redis/v9"
)

func TestBroadcastPublishesToShardedChannel(t *testing.T) {
	mr, _ := miniredis.Run()
	defer mr.Close()
	rdb := redis.NewClient(&redis.Options{Addr: mr.Addr()})
	h := New(rdb, 8)

	sid := "session-42"
	ch := h.ShardChannel(sid)
	sub := rdb.Subscribe(context.Background(), ch)
	defer sub.Close()

	err := h.Broadcast(context.Background(), sid, []byte("hello"))
	if err != nil {
		t.Fatal(err)
	}

	msg, err := sub.ReceiveMessage(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if msg.Payload != "hello" {
		t.Fatalf("want hello got %s", msg.Payload)
	}
}

func TestShardChannelConsistent(t *testing.T) {
	mr, _ := miniredis.Run()
	defer mr.Close()
	rdb := redis.NewClient(&redis.Options{Addr: mr.Addr()})
	h := New(rdb, 16)

	sid := "test-session"
	ch1 := h.ShardChannel(sid)
	ch2 := h.ShardChannel(sid)
	if ch1 != ch2 {
		t.Fatalf("same session should map to same channel: %s vs %s", ch1, ch2)
	}
}

func TestRegisterUnregister(t *testing.T) {
	mr, _ := miniredis.Run()
	defer mr.Close()
	rdb := redis.NewClient(&redis.Options{Addr: mr.Addr()})
	h := New(rdb, 8)

	ch := h.Register("session-1")
	if ch == nil {
		t.Fatal("Register returned nil channel")
	}
	h.Unregister("session-1", ch)
	// should not panic
}

func TestNewDefaultsShards(t *testing.T) {
	h := New(nil, 0)
	if h.GetShards() != 8 {
		t.Fatalf("want default 8 shards, got %d", h.GetShards())
	}
}

func TestBroadcastReturnsErrorWithoutRedis(t *testing.T) {
	h := New(nil, 8)
	err := h.Broadcast(context.Background(), "s1", []byte("x"))
	if err == nil {
		t.Fatal("expected error without redis")
	}
}

// ------------------------------------------------------------------
// D-149: Room broadcast + subscribe tests
// ------------------------------------------------------------------

func TestBroadcastToRoomPublishesToRoomChannel(t *testing.T) {
	mr, _ := miniredis.Run()
	defer mr.Close()
	rdb := redis.NewClient(&redis.Options{Addr: mr.Addr()})
	h := New(rdb, 8)

	roomID := "game-room-42"
	ch := h.RoomChannel(roomID)
	sub := rdb.Subscribe(context.Background(), ch)
	defer sub.Close()

	err := h.BroadcastToRoom(context.Background(), roomID, []byte(`{"type":"move","room_id":"game-room-42"}`))
	if err != nil {
		t.Fatal(err)
	}

	msg, err := sub.ReceiveMessage(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if msg.Payload == "" {
		t.Fatal("expected non-empty payload")
	}
}

func TestBroadcastToRoomReturnsErrorWithoutRedis(t *testing.T) {
	h := New(nil, 8)
	err := h.BroadcastToRoom(context.Background(), "room-1", []byte("x"))
	if err == nil {
		t.Fatal("expected error without redis")
	}
}

func TestBroadcastToRoomRejectsEmptyRoomID(t *testing.T) {
	mr, _ := miniredis.Run()
	defer mr.Close()
	rdb := redis.NewClient(&redis.Options{Addr: mr.Addr()})
	h := New(rdb, 8)

	err := h.BroadcastToRoom(context.Background(), "", []byte("x"))
	if err == nil {
		t.Fatal("expected error for empty room id")
	}
}

func TestRoomChannelFormat(t *testing.T) {
	h := New(nil, 8)
	ch := h.RoomChannel("room-42")
	if ch != "ep:ws:room:room-42" {
		t.Fatalf("expected ep:ws:room:room-42, got %s", ch)
	}
}

func TestRegisterRoomUnregisterRoom(t *testing.T) {
	mr, _ := miniredis.Run()
	defer mr.Close()
	rdb := redis.NewClient(&redis.Options{Addr: mr.Addr()})
	h := New(rdb, 8)

	ch := h.RegisterRoom("room-1")
	if ch == nil {
		t.Fatal("RegisterRoom returned nil channel")
	}
	h.UnregisterRoom("room-1", ch)
	// should not panic
}

func TestDeliverToRoomReachesSubscribers(t *testing.T) {
	mr, _ := miniredis.Run()
	defer mr.Close()
	rdb := redis.NewClient(&redis.Options{Addr: mr.Addr()})
	h := New(rdb, 8)

	ch1 := h.RegisterRoom("room-1")
	ch2 := h.RegisterRoom("room-1")

	h.DeliverToRoom("room-1", []byte("hello"))

	// Both subscribers should receive the message
	msg1 := <-ch1
	if string(msg1) != "hello" {
		t.Fatalf("subscriber 1: expected hello, got %s", msg1)
	}
	msg2 := <-ch2
	if string(msg2) != "hello" {
		t.Fatalf("subscriber 2: expected hello, got %s", msg2)
	}
}

func TestDeliverToSessionReachesSubscribers(t *testing.T) {
	mr, _ := miniredis.Run()
	defer mr.Close()
	rdb := redis.NewClient(&redis.Options{Addr: mr.Addr()})
	h := New(rdb, 8)

	ch1 := h.Register("session-1")
	ch2 := h.Register("session-1")

	h.DeliverToSession("session-1", []byte("hello"))

	msg1 := <-ch1
	if string(msg1) != "hello" {
		t.Fatalf("subscriber 1: expected hello, got %s", msg1)
	}
	msg2 := <-ch2
	if string(msg2) != "hello" {
		t.Fatalf("subscriber 2: expected hello, got %s", msg2)
	}
}

func TestExtractSessionID(t *testing.T) {
	tests := []struct {
		input    string
		expected string
	}{
		{`{"session_id":"abc123","data":"x"}`, "abc123"},
		{`{"type":"msg","session_id":"user-42"}`, "user-42"},
		{`{"no_session":"here"}`, ""},
		{`invalid json`, ""},
		{``, ""},
	}
	for _, tt := range tests {
		got := extractSessionID([]byte(tt.input))
		if got != tt.expected {
			t.Errorf("extractSessionID(%q) = %q, want %q", tt.input, got, tt.expected)
		}
	}
}

func TestExtractRoomID(t *testing.T) {
	tests := []struct {
		input    string
		expected string
	}{
		{`{"room_id":"game-42","move":"e2e4"}`, "game-42"},
		{`{"type":"move","room_id":"room-99"}`, "room-99"},
		{`{"no_room":"here"}`, ""},
		{`invalid`, ""},
	}
	for _, tt := range tests {
		got := extractRoomID([]byte(tt.input))
		if got != tt.expected {
			t.Errorf("extractRoomID(%q) = %q, want %q", tt.input, got, tt.expected)
		}
	}
}

func TestStartSubscribeNoRedis(t *testing.T) {
	// StartSubscribe with nil Redis should be a no-op (not panic)
	h := New(nil, 8)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	h.StartSubscribe(ctx) // should not panic
}
