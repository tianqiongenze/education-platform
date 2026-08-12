package srs

import (
	"context"
	"errors"
	"testing"

	"github.com/alicebob/miniredis/v2"
	"github.com/redis/go-redis/v9"
)

// newTestRouter spins up a miniredis and an OriginRouter with fixed origins.
func newTestRouter(t *testing.T, origins []string) (*OriginRouter, *miniredis.Miniredis) {
	t.Helper()
	mr, err := miniredis.Run()
	if err != nil {
		t.Fatalf("miniredis: %v", err)
	}
	t.Cleanup(mr.Close)
	rdb := redis.NewClient(&redis.Options{Addr: mr.Addr()})
	t.Cleanup(func() { _ = rdb.Close() })
	r, err := NewOriginRouterWithOrigins(rdb, origins)
	if err != nil {
		t.Fatalf("NewOriginRouterWithOrigins: %v", err)
	}
	return r, mr
}

func TestNewOriginRouter_EmptyOrigins(t *testing.T) {
	mr, err := miniredis.Run()
	if err != nil {
		t.Fatalf("miniredis: %v", err)
	}
	defer mr.Close()
	rdb := redis.NewClient(&redis.Options{Addr: mr.Addr()})
	defer rdb.Close()

	_, err = NewOriginRouterWithOrigins(rdb, nil)
	if !errors.Is(err, ErrNoOrigins) {
		t.Fatalf("want ErrNoOrigins, got %v", err)
	}
}

func TestClaimStream_FirstClaimWins(t *testing.T) {
	r, _ := newTestRouter(t, []string{"o1:1985", "o2:1985"})
	ctx := context.Background()

	ok, err := r.ClaimStream(ctx, "room-1", "o1:1985")
	if err != nil || !ok {
		t.Fatalf("first claim: ok=%v err=%v", ok, err)
	}

	// Second claim by a different origin must fail.
	ok2, err2 := r.ClaimStream(ctx, "room-1", "o2:1985")
	if ok2 {
		t.Fatalf("second claim should not succeed")
	}
	if !errors.Is(err2, ErrAlreadyClaimed) {
		t.Fatalf("want ErrAlreadyClaimed, got %v", err2)
	}
}

func TestGetOrigin_ReturnsOwner(t *testing.T) {
	r, _ := newTestRouter(t, []string{"o1:1985"})
	ctx := context.Background()

	owner, err := r.GetOrigin(ctx, "room-x")
	if err != nil || owner != "" {
		t.Fatalf("unclaimed room: owner=%q err=%v", owner, err)
	}

	if _, err := r.ClaimStream(ctx, "room-x", "o1:1985"); err != nil {
		t.Fatalf("claim: %v", err)
	}
	owner, err = r.GetOrigin(ctx, "room-x")
	if err != nil || owner != "o1:1985" {
		t.Fatalf("claimed room: owner=%q err=%v", owner, err)
	}
}

func TestReleaseStream_DeletesBinding(t *testing.T) {
	r, _ := newTestRouter(t, []string{"o1:1985"})
	ctx := context.Background()

	if _, err := r.ClaimStream(ctx, "room-r", "o1:1985"); err != nil {
		t.Fatalf("claim: %v", err)
	}
	if err := r.ReleaseStream(ctx, "room-r"); err != nil {
		t.Fatalf("release: %v", err)
	}

	owner, err := r.GetOrigin(ctx, "room-r")
	if err != nil || owner != "" {
		t.Fatalf("after release owner should be empty, got %q err=%v", owner, err)
	}
	// Re-claim must now succeed (release freed the binding).
	ok, err := r.ClaimStream(ctx, "room-r", "o2:1985")
	if err != nil || !ok {
		t.Fatalf("reclaim after release: ok=%v err=%v", ok, err)
	}
}

func TestReleaseStream_Idempotent(t *testing.T) {
	r, _ := newTestRouter(t, []string{"o1:1985"})
	ctx := context.Background()
	if err := r.ReleaseStream(ctx, "never-claimed"); err != nil {
		t.Fatalf("release on unclaimed stream should not error: %v", err)
	}
}

func TestSelectOrigin_PicksLeastLoaded(t *testing.T) {
	r, mr := newTestRouter(t, []string{"o1:1985", "o2:1985", "o3:1985"})
	ctx := context.Background()

	// Artificially set loads: o1=5, o2=1, o3=3 → expect o2.
	mr.Set("origin:load:o1:1985", "5")
	mr.Set("origin:load:o2:1985", "1")
	mr.Set("origin:load:o3:1985", "3")

	got, err := r.SelectOrigin(ctx)
	if err != nil {
		t.Fatalf("select: %v", err)
	}
	if got != "o2:1985" {
		t.Fatalf("want least-loaded o2:1985, got %s", got)
	}
}

func TestSelectOrigin_NoLoadInfo_PicksFirst(t *testing.T) {
	r, _ := newTestRouter(t, []string{"o1:1985", "o2:1985"})
	ctx := context.Background()
	// No load keys set → all zero → first in order wins.
	got, err := r.SelectOrigin(ctx)
	if err != nil {
		t.Fatalf("select: %v", err)
	}
	if got != "o1:1985" {
		t.Fatalf("want o1:1985, got %s", got)
	}
}

func TestClaimStream_IncrementsLoad(t *testing.T) {
	r, mr := newTestRouter(t, []string{"o1:1985"})
	ctx := context.Background()
	if _, err := r.ClaimStream(ctx, "room-l", "o1:1985"); err != nil {
		t.Fatalf("claim: %v", err)
	}
	load, err := mr.Get("origin:load:o1:1985")
	if err != nil || load != "1" {
		t.Fatalf("load after one claim: want 1, got %q (err=%v)", load, err)
	}
}

func TestReleaseStream_DecrementsLoad(t *testing.T) {
	r, mr := newTestRouter(t, []string{"o1:1985"})
	ctx := context.Background()
	if _, err := r.ClaimStream(ctx, "room-d", "o1:1985"); err != nil {
		t.Fatalf("claim: %v", err)
	}
	if err := r.ReleaseStream(ctx, "room-d"); err != nil {
		t.Fatalf("release: %v", err)
	}
	load, err := mr.Get("origin:load:o1:1985")
	if err != nil || load != "0" {
		t.Fatalf("load after release: want 0, got %q (err=%v)", load, err)
	}
}
