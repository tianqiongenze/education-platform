package yjs

import (
	"bytes"
	"sync"
	"testing"
)

// D-186: YjsDocument tests

func TestNewYjsDocument(t *testing.T) {
	doc := NewYjsDocument("doc-1")
	if doc == nil {
		t.Fatal("NewYjsDocument returned nil")
	}
	if doc.DocID() != "doc-1" {
		t.Fatalf("expected DocID doc-1, got %s", doc.DocID())
	}
	if doc.UpdateCount() != 0 {
		t.Fatalf("expected 0 updates, got %d", doc.UpdateCount())
	}
}

func TestYjsDocumentApplyUpdate(t *testing.T) {
	doc := NewYjsDocument("doc-1")
	doc.ApplyUpdate([]byte{0x01, 0x02, 0x03})
	if doc.UpdateCount() != 1 {
		t.Fatalf("expected 1 update, got %d", doc.UpdateCount())
	}
}

func TestYjsDocumentApplyUpdateEmpty(t *testing.T) {
	doc := NewYjsDocument("doc-1")
	doc.ApplyUpdate(nil)
	doc.ApplyUpdate([]byte{})
	if doc.UpdateCount() != 0 {
		t.Fatalf("empty updates should be ignored, got %d", doc.UpdateCount())
	}
}

func TestYjsDocumentApplyUpdateClones(t *testing.T) {
	doc := NewYjsDocument("doc-1")
	original := []byte{0x01, 0x02}
	doc.ApplyUpdate(original)
	original[0] = 0xFF
	snap := doc.Snapshot()
	if snap[0] == 0xFF {
		t.Fatal("ApplyUpdate should clone the input, not alias it")
	}
}

func TestYjsDocumentSnapshotEmpty(t *testing.T) {
	doc := NewYjsDocument("doc-1")
	snap := doc.Snapshot()
	if len(snap) != 0 {
		t.Fatalf("expected empty snapshot, got %d bytes", len(snap))
	}
}

func TestYjsDocumentSnapshotFlattens(t *testing.T) {
	doc := NewYjsDocument("doc-1")
	doc.ApplyUpdate([]byte{0x01, 0x02})
	doc.ApplyUpdate([]byte{0x03, 0x04, 0x05})
	snap := doc.Snapshot()
	expected := []byte{0x01, 0x02, 0x03, 0x04, 0x05}
	if !bytes.Equal(snap, expected) {
		t.Fatalf("expected %v, got %v", expected, snap)
	}
}

func TestYjsDocumentAwareness(t *testing.T) {
	doc := NewYjsDocument("doc-1")
	doc.ApplyAwarenessUpdate([]byte{0xAA, 0xBB})
	awareness := doc.Awareness()
	if len(awareness) != 2 {
		t.Fatalf("expected 2 awareness bytes, got %d", len(awareness))
	}
	if awareness[0] != 0xAA {
		t.Fatalf("expected 0xAA, got %02x", awareness[0])
	}
}

func TestYjsDocumentAwarenessEmpty(t *testing.T) {
	doc := NewYjsDocument("doc-1")
	awareness := doc.Awareness()
	if len(awareness) != 0 {
		t.Fatalf("expected empty awareness, got %d bytes", len(awareness))
	}
}

// D-186: HandleSyncMessage tests

func TestHandleSyncMessageSyncStep1(t *testing.T) {
	doc := NewYjsDocument("doc-1")
	doc.ApplyUpdate([]byte{0x10, 0x20})
	// SYNC_STEP1 = [0]
	reply, hasReply := doc.HandleSyncMessage([]byte{MsgSyncStep1})
	if !hasReply {
		t.Fatal("SYNC_STEP1 should produce a reply")
	}
	if len(reply) == 0 {
		t.Fatal("reply should not be empty")
	}
	if reply[0] != MsgSyncStep2 {
		t.Fatalf("expected SYNC_STEP2, got %d", reply[0])
	}
	expected := []byte{MsgSyncStep2, 0x10, 0x20}
	if !bytes.Equal(reply, expected) {
		t.Fatalf("expected %v, got %v", expected, reply)
	}
}

func TestHandleSyncMessageSyncStep2(t *testing.T) {
	doc := NewYjsDocument("doc-1")
	msg := append([]byte{MsgSyncStep2}, []byte{0x01, 0x02}...)
	reply, hasReply := doc.HandleSyncMessage(msg)
	if hasReply {
		t.Fatal("SYNC_STEP2 should not produce a reply")
	}
	if reply != nil {
		t.Fatal("reply should be nil for SYNC_STEP2")
	}
	if doc.UpdateCount() != 1 {
		t.Fatalf("expected 1 update applied, got %d", doc.UpdateCount())
	}
}

func TestHandleSyncMessageAwarenessUpdate(t *testing.T) {
	doc := NewYjsDocument("doc-1")
	msg := append([]byte{MsgAwarenessUpdate}, []byte{0x09}...)
	_, hasReply := doc.HandleSyncMessage(msg)
	if hasReply {
		t.Fatal("AWARENESS_UPDATE should not produce a reply")
	}
	awareness := doc.Awareness()
	if len(awareness) != 1 || awareness[0] != 0x09 {
		t.Fatalf("expected awareness [0x09], got %v", awareness)
	}
}

func TestHandleSyncMessageAwarenessQuery(t *testing.T) {
	doc := NewYjsDocument("doc-1")
	doc.ApplyAwarenessUpdate([]byte{0x07})
	reply, hasReply := doc.HandleSyncMessage([]byte{MsgAwarenessQuery})
	if !hasReply {
		t.Fatal("AWARENESS_QUERY should produce a reply")
	}
	if reply[0] != MsgAwarenessUpdate {
		t.Fatalf("expected AWARENESS_UPDATE, got %d", reply[0])
	}
	if len(reply) != 2 || reply[1] != 0x07 {
		t.Fatalf("expected [AWARENESS_UPDATE, 0x07], got %v", reply)
	}
}

func TestHandleSyncMessageUpdate(t *testing.T) {
	doc := NewYjsDocument("doc-1")
	msg := append([]byte{MsgUpdate}, []byte{0x42}...)
	_, hasReply := doc.HandleSyncMessage(msg)
	if hasReply {
		t.Fatal("UPDATE should not produce a reply")
	}
	if doc.UpdateCount() != 1 {
		t.Fatalf("expected 1 update, got %d", doc.UpdateCount())
	}
}

func TestHandleSyncMessageEmpty(t *testing.T) {
	doc := NewYjsDocument("doc-1")
	_, hasReply := doc.HandleSyncMessage(nil)
	if hasReply {
		t.Fatal("empty message should not produce a reply")
	}
}

func TestHandleSyncMessageUnknownType(t *testing.T) {
	doc := NewYjsDocument("doc-1")
	_, hasReply := doc.HandleSyncMessage([]byte{0xFF, 0x00})
	if hasReply {
		t.Fatal("unknown type should not produce a reply")
	}
}

func TestEncodeUpdate(t *testing.T) {
	encoded := EncodeUpdate([]byte{0x01, 0x02})
	expected := []byte{MsgUpdate, 0x01, 0x02}
	if !bytes.Equal(encoded, expected) {
		t.Fatalf("expected %v, got %v", expected, encoded)
	}
}

func TestEncodeAwareness(t *testing.T) {
	encoded := EncodeAwareness([]byte{0x03})
	expected := []byte{MsgAwarenessUpdate, 0x03}
	if !bytes.Equal(encoded, expected) {
		t.Fatalf("expected %v, got %v", expected, encoded)
	}
}

// D-186: Client/YjsRoom tests

func TestNewYjsRoom(t *testing.T) {
	room := NewYjsRoom("session-1")
	if room == nil {
		t.Fatal("NewYjsRoom returned nil")
	}
	if room.SessionID() != "session-1" {
		t.Fatalf("expected session-1, got %s", room.SessionID())
	}
	if room.ClientCount() != 0 {
		t.Fatalf("expected 0 clients, got %d", room.ClientCount())
	}
	if room.Doc() == nil {
		t.Fatal("room doc should not be nil")
	}
}

func TestYjsRoomJoinLeave(t *testing.T) {
	room := NewYjsRoom("session-1")
	c := &Client{ID: "c1", UserID: "u1", Send: func([]byte) bool { return true }}

	if !room.Join(c) {
		t.Fatal("Join should return true for new client")
	}
	if room.ClientCount() != 1 {
		t.Fatalf("expected 1 client, got %d", room.ClientCount())
	}

	// duplicate join
	if room.Join(c) {
		t.Fatal("Join should return false for duplicate client")
	}
	if room.ClientCount() != 1 {
		t.Fatalf("expected 1 client after duplicate, got %d", room.ClientCount())
	}

	room.Leave("c1")
	if room.ClientCount() != 0 {
		t.Fatalf("expected 0 clients after leave, got %d", room.ClientCount())
	}

	// leaving non-existent client is safe
	room.Leave("nonexistent")
}

func TestYjsRoomBroadcastUpdate(t *testing.T) {
	room := NewYjsRoom("session-1")

	var mu sync.Mutex
	received1 := [][]byte{}
	received2 := [][]byte{}

	c1 := &Client{ID: "c1", UserID: "u1", Send: func(msg []byte) bool {
		mu.Lock()
		defer mu.Unlock()
		received1 = append(received1, msg)
		return true
	}}
	c2 := &Client{ID: "c2", UserID: "u2", Send: func(msg []byte) bool {
		mu.Lock()
		defer mu.Unlock()
		received2 = append(received2, msg)
		return true
	}}
	originator := &Client{ID: "origin", UserID: "u3", Send: func(msg []byte) bool {
		return true
	}}

	room.Join(c1)
	room.Join(c2)
	room.Join(originator)

	msg := []byte{MsgUpdate, 0x01, 0x02}
	delivered := room.BroadcastUpdate(msg, "origin")
	if delivered != 2 {
		t.Fatalf("expected 2 deliveries, got %d", delivered)
	}

	mu.Lock()
	defer mu.Unlock()
	if len(received1) != 1 {
		t.Fatalf("c1 should receive 1 message, got %d", len(received1))
	}
	if len(received2) != 1 {
		t.Fatalf("c2 should receive 1 message, got %d", len(received2))
	}
	if !bytes.Equal(received1[0], msg) {
		t.Fatalf("c1 received wrong message: %v", received1[0])
	}
}

func TestYjsRoomBroadcastUpdateFullSendReturnsFalse(t *testing.T) {
	room := NewYjsRoom("session-1")
	// Client whose Send always returns false (full buffer)
	c := &Client{ID: "c1", UserID: "u1", Send: func([]byte) bool { return false }}
	room.Join(c)

	delivered := room.BroadcastUpdate([]byte{MsgUpdate, 0x01}, "other")
	if delivered != 0 {
		t.Fatalf("expected 0 deliveries when Send fails, got %d", delivered)
	}
}

func TestYjsRoomApplyUpdate(t *testing.T) {
	room := NewYjsRoom("session-1")

	var received [][]byte
	var mu sync.Mutex
	other := &Client{ID: "other", UserID: "u2", Send: func(msg []byte) bool {
		mu.Lock()
		defer mu.Unlock()
		received = append(received, msg)
		return true
	}}
	originator := &Client{ID: "origin", UserID: "u1", Send: func([]byte) bool { return true }}

	room.Join(originator)
	room.Join(other)

	delivered := room.ApplyUpdate([]byte{0x01, 0x02}, "origin")
	if delivered != 1 {
		t.Fatalf("expected 1 delivery, got %d", delivered)
	}
	if room.Doc().UpdateCount() != 1 {
		t.Fatalf("expected 1 update on doc, got %d", room.Doc().UpdateCount())
	}

	mu.Lock()
	defer mu.Unlock()
	if len(received) != 1 {
		t.Fatalf("other should receive 1 broadcast, got %d", len(received))
	}
	// broadcast should be encoded as UPDATE
	if received[0][0] != MsgUpdate {
		t.Fatalf("expected UPDATE type, got %d", received[0][0])
	}
}

func TestYjsRoomApplyAwarenessUpdate(t *testing.T) {
	room := NewYjsRoom("session-1")

	var received [][]byte
	var mu sync.Mutex
	other := &Client{ID: "other", UserID: "u2", Send: func(msg []byte) bool {
		mu.Lock()
		defer mu.Unlock()
		received = append(received, msg)
		return true
	}}
	originator := &Client{ID: "origin", UserID: "u1", Send: func([]byte) bool { return true }}

	room.Join(originator)
	room.Join(other)

	delivered := room.ApplyAwarenessUpdate([]byte{0x09}, "origin")
	if delivered != 1 {
		t.Fatalf("expected 1 delivery, got %d", delivered)
	}

	mu.Lock()
	defer mu.Unlock()
	if len(received) != 1 {
		t.Fatalf("other should receive 1 broadcast, got %d", len(received))
	}
	if received[0][0] != MsgAwarenessUpdate {
		t.Fatalf("expected AWARENESS_UPDATE type, got %d", received[0][0])
	}
}

func TestYjsRoomHandleClientMessageSyncStep1(t *testing.T) {
	room := NewYjsRoom("session-1")
	room.Doc().ApplyUpdate([]byte{0x10})

	var received [][]byte
	var mu sync.Mutex
	c := &Client{ID: "c1", UserID: "u1", Send: func(msg []byte) bool {
		mu.Lock()
		defer mu.Unlock()
		received = append(received, msg)
		return true
	}}
	room.Join(c)

	// SYNC_STEP1 — should reply with SYNC_STEP2 + snapshot
	room.HandleClientMessage("c1", []byte{MsgSyncStep1})

	mu.Lock()
	defer mu.Unlock()
	if len(received) != 1 {
		t.Fatalf("expected 1 reply, got %d", len(received))
	}
	if received[0][0] != MsgSyncStep2 {
		t.Fatalf("expected SYNC_STEP2, got %d", received[0][0])
	}
}

func TestYjsRoomHandleClientMessageUpdate(t *testing.T) {
	room := NewYjsRoom("session-1")

	var received [][]byte
	var mu sync.Mutex
	other := &Client{ID: "other", UserID: "u2", Send: func(msg []byte) bool {
		mu.Lock()
		defer mu.Unlock()
		received = append(received, msg)
		return true
	}}
	originator := &Client{ID: "origin", UserID: "u1", Send: func([]byte) bool { return true }}

	room.Join(originator)
	room.Join(other)

	msg := append([]byte{MsgUpdate}, []byte{0x42}...)
	room.HandleClientMessage("origin", msg)

	if room.Doc().UpdateCount() != 1 {
		t.Fatalf("expected 1 update on doc, got %d", room.Doc().UpdateCount())
	}

	mu.Lock()
	defer mu.Unlock()
	if len(received) != 1 {
		t.Fatalf("other should receive 1 broadcast, got %d", len(received))
	}
}

func TestYjsRoomHandleClientMessageEmpty(t *testing.T) {
	room := NewYjsRoom("session-1")
	c := &Client{ID: "c1", UserID: "u1", Send: func([]byte) bool { return true }}
	room.Join(c)

	// should not panic
	room.HandleClientMessage("c1", nil)
	room.HandleClientMessage("c1", []byte{})
}

// D-186: RoomRegistry tests

func TestNewRoomRegistry(t *testing.T) {
	reg := NewRoomRegistry()
	if reg == nil {
		t.Fatal("NewRoomRegistry returned nil")
	}
	if reg.Count() != 0 {
		t.Fatalf("expected 0 rooms, got %d", reg.Count())
	}
}

func TestRoomRegistryGetOrCreate(t *testing.T) {
	reg := NewRoomRegistry()
	r1 := reg.GetOrCreate("session-1")
	if r1 == nil {
		t.Fatal("GetOrCreate returned nil")
	}
	if reg.Count() != 1 {
		t.Fatalf("expected 1 room, got %d", reg.Count())
	}

	// same id returns the same room
	r2 := reg.GetOrCreate("session-1")
	if r1 != r2 {
		t.Fatal("GetOrCreate should return same room for same id")
	}
	if reg.Count() != 1 {
		t.Fatalf("expected 1 room after duplicate GetOrCreate, got %d", reg.Count())
	}
}

func TestRoomRegistryGet(t *testing.T) {
	reg := NewRoomRegistry()
	reg.GetOrCreate("session-1")

	r := reg.Get("session-1")
	if r == nil {
		t.Fatal("Get should return the room")
	}
	if r.SessionID() != "session-1" {
		t.Fatalf("expected session-1, got %s", r.SessionID())
	}

	missing := reg.Get("nonexistent")
	if missing != nil {
		t.Fatal("Get should return nil for missing room")
	}
}

func TestRoomRegistryRemove(t *testing.T) {
	reg := NewRoomRegistry()
	reg.GetOrCreate("session-1")
	if reg.Count() != 1 {
		t.Fatalf("expected 1 room, got %d", reg.Count())
	}

	reg.Remove("session-1")
	if reg.Count() != 0 {
		t.Fatalf("expected 0 rooms after remove, got %d", reg.Count())
	}
	if reg.Get("session-1") != nil {
		t.Fatal("Get should return nil after remove")
	}

	// removing non-existent is safe
	reg.Remove("nonexistent")
}

// D-186: Concurrency test for RoomRegistry
func TestRoomRegistryConcurrentAccess(t *testing.T) {
	reg := NewRoomRegistry()
	var wg sync.WaitGroup

	for i := 0; i < 100; i++ {
		wg.Add(1)
		go func(n int) {
			defer wg.Done()
			id := "session-concurrent"
			reg.GetOrCreate(id)
			reg.Get(id)
		}(i)
	}
	wg.Wait()

	if reg.Count() != 1 {
		t.Fatalf("expected 1 room after concurrent access, got %d", reg.Count())
	}
}
