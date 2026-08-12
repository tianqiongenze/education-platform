// Package livekit — sfu.go manages LiveKit SFU room lifecycle for the live-go
// service (D-187).
//
// SfuManager maps roomId → LiveKit room info and delegates token/room creation
// to a LiveKitClient. The map is process-local; cross-replica coordination is
// not needed because LiveKit itself is the source of truth for room state —
// the map is only a cache to avoid redundant CreateRoom calls.
//
// Concurrency: SfuManager is safe for concurrent use (sync.RWMutex around the
// map).
package livekit

import (
	"context"
	"fmt"
	"sync"
)

// SfuRoomInfo is the cached LiveKit room state.
type SfuRoomInfo struct {
	RoomID      string
	SID         string
	Name        string
	ViewerLimit int
}

// SfuManager creates/closes SFU rooms and mints participant tokens.
type SfuManager struct {
	client LiveKitClient

	mu    sync.RWMutex
	rooms map[string]*SfuRoomInfo
}

// NewSfuManager builds an SfuManager bound to a LiveKitClient. If client is
// nil (LiveKit not configured), CreateSfuRoom and GenerateToken return
// EPDevOnly — callers should fall back to broadcast mode.
func NewSfuManager(client LiveKitClient) *SfuManager {
	return &SfuManager{
		client: client,
		rooms:  map[string]*SfuRoomInfo{},
	}
}

// Configured reports whether a LiveKit client is wired in.
func (m *SfuManager) Configured() bool {
	return m.client != nil
}

// CreateSfuRoom creates a LiveKit room and caches its info.
func (m *SfuManager) CreateSfuRoom(ctx context.Context, roomID string, viewerLimit int) (*SfuRoomInfo, error) {
	if m.client == nil {
		return nil, EPDevOnly
	}
	if roomID == "" {
		return nil, fmt.Errorf("livekit: roomId must not be empty")
	}
	// Idempotent: if we already cached the room, return it.
	m.mu.RLock()
	if info, ok := m.rooms[roomID]; ok {
		m.mu.RUnlock()
		return info, nil
	}
	m.mu.RUnlock()

	resp, err := m.client.CreateRoom(ctx, CreateRoomRequest{
		RoomID:      roomID,
		Name:        roomID,
		ViewerLimit: viewerLimit,
	})
	if err != nil {
		return nil, fmt.Errorf("livekit: create room %s: %w", roomID, err)
	}
	info := &SfuRoomInfo{
		RoomID:      roomID,
		SID:         resp.SID,
		Name:        resp.Name,
		ViewerLimit: viewerLimit,
	}
	m.mu.Lock()
	m.rooms[roomID] = info
	m.mu.Unlock()
	return info, nil
}

// GenerateToken mints a participant token for an SFU room.
func (m *SfuManager) GenerateToken(ctx context.Context, roomID, userID, role string) (*CreateTokenResponse, error) {
	if m.client == nil {
		return nil, EPDevOnly
	}
	if roomID == "" || userID == "" {
		return nil, fmt.Errorf("livekit: roomId and userId must not be empty")
	}
	resp, err := m.client.CreateToken(ctx, CreateTokenRequest{
		RoomID: roomID,
		UserID: userID,
		Role:   role,
	})
	if err != nil {
		return nil, fmt.Errorf("livekit: generate token for %s: %w", roomID, err)
	}
	return resp, nil
}

// CloseSfuRoom removes the room from the local cache. It does not call
// LiveKit DeleteRoom — LiveKit rooms auto-expire when the last participant
// leaves. This is safe to call on an unknown room.
func (m *SfuManager) CloseSfuRoom(ctx context.Context, roomID string) {
	m.mu.Lock()
	delete(m.rooms, roomID)
	m.mu.Unlock()
}

// GetRoom returns the cached info for a room, or nil.
func (m *SfuManager) GetRoom(roomID string) *SfuRoomInfo {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.rooms[roomID]
}
