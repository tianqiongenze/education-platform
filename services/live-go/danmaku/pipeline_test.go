package danmaku

import (
	"context"
	"encoding/json"
	"testing"
	"time"

	"github.com/alicebob/miniredis/v2"
	"github.com/redis/go-redis/v9"
)

func newTestPipeline(t *testing.T) (*DanmakuPipeline, *miniredis.Miniredis) {
	t.Helper()
	mr, err := miniredis.Run()
	if err != nil {
		t.Fatalf("miniredis: %v", err)
	}
	t.Cleanup(mr.Close)
	rdb := redis.NewClient(&redis.Options{Addr: mr.Addr()})
	t.Cleanup(func() { _ = rdb.Close() })
	return NewDanmakuPipeline(rdb), mr
}

func TestSubmit_PushesToList(t *testing.T) {
	p, mr := newTestPipeline(t)
	ctx := context.Background()

	if err := p.Submit(ctx, "room-1", "user-10", "hello"); err != nil {
		t.Fatalf("submit: %v", err)
	}
	items, err := mr.List("danmaku:room-1")
	if err != nil {
		t.Fatalf("list: %v", err)
	}
	if len(items) != 1 {
		t.Fatalf("list len: want 1, got %d", len(items))
	}

	// Verify payload shape.
	raw := items[0]
	var entry map[string]any
	if err := json.Unmarshal([]byte(raw), &entry); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if entry["userId"] != "user-10" {
		t.Errorf("userId: want user-10, got %v", entry["userId"])
	}
	if entry["message"] != "hello" {
		t.Errorf("message: want hello, got %v", entry["message"])
	}
	if _, ok := entry["ts"]; !ok {
		t.Errorf("ts missing")
	}
}

func TestSubmit_EmptyRoomOrUser(t *testing.T) {
	p, _ := newTestPipeline(t)
	ctx := context.Background()
	if err := p.Submit(ctx, "", "u", "m"); err == nil {
		t.Errorf("empty roomID should error")
	}
	if err := p.Submit(ctx, "r", "", "m"); err == nil {
		t.Errorf("empty userID should error")
	}
}

func TestBatch_BroadcastsToSubscriber(t *testing.T) {
	p, _ := newTestPipeline(t)
	ctx := context.Background()

	ch, unsub := p.Subscribe("room-b")
	defer unsub()

	// Submit a few messages then manually flush (skip the 100ms ticker).
	for i := 0; i < 3; i++ {
		if err := p.Submit(ctx, "room-b", "u", "msg"); err != nil {
			t.Fatalf("submit %d: %v", i, err)
		}
	}

	// flush is unexported but callable within the package.
	p.flush(ctx, "room-b")

	select {
	case payload := <-ch:
		var batch map[string]any
		if err := json.Unmarshal(payload, &batch); err != nil {
			t.Fatalf("unmarshal batch: %v", err)
		}
		msgs, ok := batch["messages"].([]any)
		if !ok {
			t.Fatalf("batch messages not a slice: %T", batch["messages"])
		}
		if len(msgs) != 3 {
			t.Fatalf("batch len: want 3, got %d", len(msgs))
		}
		if batch["roomId"] != "room-b" {
			t.Errorf("roomId: want room-b, got %v", batch["roomId"])
		}
	case <-time.After(time.Second):
		t.Fatalf("timed out waiting for batch")
	}
}

func TestBatch_EmptyListDoesNotBroadcast(t *testing.T) {
	p, _ := newTestPipeline(t)
	ctx := context.Background()

	ch, unsub := p.Subscribe("room-empty")
	defer unsub()

	p.flush(ctx, "room-empty")

	select {
	case payload := <-ch:
		t.Fatalf("unexpected broadcast: %s", payload)
	case <-time.After(50 * time.Millisecond):
		// ok
	}
}

func TestStartBatcher_FlushesOnInterval(t *testing.T) {
	p, _ := newTestPipeline(t)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	ch, unsub := p.Subscribe("room-t")
	defer unsub()

	stop := p.StartBatcher(ctx, "room-t")
	defer stop()

	if err := p.Submit(ctx, "room-t", "u", "batched"); err != nil {
		t.Fatalf("submit: %v", err)
	}

	select {
	case payload := <-ch:
		var batch map[string]any
		if err := json.Unmarshal(payload, &batch); err != nil {
			t.Fatalf("unmarshal: %v", err)
		}
		msgs := batch["messages"].([]any)
		if len(msgs) != 1 {
			t.Fatalf("want 1 message, got %d", len(msgs))
		}
	case <-time.After(2 * time.Second):
		t.Fatalf("batcher did not flush within 2s")
	}
}

func TestSubscribe_MultipleSubscribers(t *testing.T) {
	p, _ := newTestPipeline(t)
	ctx := context.Background()

	ch1, unsub1 := p.Subscribe("room-m")
	defer unsub1()
	ch2, unsub2 := p.Subscribe("room-m")
	defer unsub2()

	if err := p.Submit(ctx, "room-m", "u", "hi"); err != nil {
		t.Fatalf("submit: %v", err)
	}
	p.flush(ctx, "room-m")

	for i, ch := range []<-chan []byte{ch1, ch2} {
		select {
		case <-ch:
		case <-time.After(time.Second):
			t.Fatalf("subscriber %d did not receive batch", i)
		}
	}
	if got := p.SubscriberCount("room-m"); got != 2 {
		t.Fatalf("subscriber count: want 2, got %d", got)
	}
}

func TestUnsubscribe_RemovesSubscriber(t *testing.T) {
	p, _ := newTestPipeline(t)
	_, unsub := p.Subscribe("room-u")
	unsub()
	if got := p.SubscriberCount("room-u"); got != 0 {
		t.Fatalf("after unsub: want 0, got %d", got)
	}
}
