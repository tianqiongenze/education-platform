// Package livekit wraps the LiveKit server twirp API for the live-go service
// (D-187).
//
// client.go defines the LiveKitClient interface and a default HTTP client that
// talks to a LiveKit server via its Twirp endpoints. The interface is kept
// minimal (CreateRoom, CreateToken, RemoveParticipant, MutePublishedTrack) so
// the SFU manager can be unit-tested with a fake.
//
// LiveKit exposes a protobuf-over-Twirp API; to avoid pulling the generated
// stubs (which require the livekit-protocol Go module, unavailable offline),
// this client sends hand-rolled JSON to the /twirp/ endpoints. LiveKit's Twirp
// server accepts JSON when the Content-Type is application/json.
package livekit

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"time"
)

// EPDevOnly is the error returned when LiveKit is not configured. Per the
// platform graceful-degradation policy, the Go service keeps serving SRS-based
// broadcast rooms even when the SFU is absent.
var EPDevOnly = errors.New("livekit: not configured (LIVEKIT_URL empty) — interactive mode unavailable in this environment")

// CreateRoomRequest creates an SFU room.
type CreateRoomRequest struct {
	RoomID      string `json:"roomId"`
	Name        string `json:"name"`
	ViewerLimit int    `json:"viewerLimit,omitempty"`
}

// CreateRoomResponse is the LiveKit room info returned after creation.
type CreateRoomResponse struct {
	RoomID string `json:"roomId"`
	SID    string `json:"sid"`
	Name   string `json:"name"`
}

// CreateTokenRequest mints a participant token.
type CreateTokenRequest struct {
	RoomID string `json:"roomId"`
	UserID string `json:"userId"`
	Role   string `json:"role"` // "publisher" | "subscriber"
}

// CreateTokenResponse carries the JWT the client uses to connect.
type CreateTokenResponse struct {
	Token string `json:"token"`
	URL   string `json:"url"`
}

// RemoveParticipantRequest removes a participant from an SFU room.
type RemoveParticipantRequest struct {
	RoomID   string `json:"roomId"`
	Identity string `json:"identity"`
}

// MuteTrackRequest mutes/unmutes a published track.
type MuteTrackRequest struct {
	RoomID    string `json:"roomId"`
	Identity  string `json:"identity"`
	TrackSID  string `json:"trackSid"`
	Muted     bool   `json:"muted"`
}

// LiveKitClient is the subset of LiveKit operations live-go needs. The SFU
// manager depends on this interface so tests can inject a fake.
type LiveKitClient interface {
	CreateRoom(ctx context.Context, req CreateRoomRequest) (*CreateRoomResponse, error)
	CreateToken(ctx context.Context, req CreateTokenRequest) (*CreateTokenResponse, error)
	RemoveParticipant(ctx context.Context, req RemoveParticipantRequest) error
	MutePublishedTrack(ctx context.Context, req MuteTrackRequest) error
}

// DefaultLiveKitClient calls a LiveKit server over HTTP+Twirp(JSON).
//
// Concurrency: the embedded http.Client is goroutine-safe; the struct is
// immutable after construction.
type DefaultLiveKitClient struct {
	baseURL   string
	apiKey    string
	apiSecret string
	client    *http.Client
}

// NewDefaultClient reads LIVEKIT_URL, LIVEKIT_API_KEY, LIVEKIT_API_SECRET from
// the environment. Returns EPDevOnly when LIVEKIT_URL is empty.
func NewDefaultClient() (LiveKitClient, error) {
	url := os.Getenv("LIVEKIT_URL")
	if url == "" {
		return nil, EPDevOnly
	}
	return &DefaultLiveKitClient{
		baseURL:   url,
		apiKey:    os.Getenv("LIVEKIT_API_KEY"),
		apiSecret: os.Getenv("LIVEKIT_API_SECRET"),
		client:    &http.Client{Timeout: 10 * time.Second},
	}, nil
}

// NewDefaultClientWith is the test-friendly constructor.
func NewDefaultClientWith(baseURL, apiKey, apiSecret string) LiveKitClient {
	return &DefaultLiveKitClient{
		baseURL:   baseURL,
		apiKey:    apiKey,
		apiSecret: apiSecret,
		client:    &http.Client{Timeout: 10 * time.Second},
	}
}

// twirpPost sends a JSON Twirp request to the LiveKit server.
func (c *DefaultLiveKitClient) twirpPost(ctx context.Context, method string, body any, out any) error {
	payload, err := json.Marshal(body)
	if err != nil {
		return fmt.Errorf("livekit: marshal %s: %w", method, err)
	}
	url := c.baseURL + "/twirp/livekit.RoomService/" + method
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, url, bytes.NewReader(payload))
	if err != nil {
		return fmt.Errorf("livekit: build %s request: %w", method, err)
	}
	req.Header.Set("Content-Type", "application/json")
	// LiveKit accepts API key/secret as Basic auth on Twirp.
	req.SetBasicAuth(c.apiKey, c.apiSecret)

	resp, err := c.client.Do(req)
	if err != nil {
		return fmt.Errorf("livekit: %s call: %w", method, err)
	}
	defer resp.Body.Close()

	respBody, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return fmt.Errorf("livekit: %s returned %d: %s", method, resp.StatusCode, string(respBody))
	}
	if out != nil {
		if err := json.Unmarshal(respBody, out); err != nil {
			return fmt.Errorf("livekit: decode %s response: %w", method, err)
		}
	}
	return nil
}

// CreateRoom creates a LiveKit room.
func (c *DefaultLiveKitClient) CreateRoom(ctx context.Context, req CreateRoomRequest) (*CreateRoomResponse, error) {
	var out CreateRoomResponse
	if err := c.twirpPost(ctx, "CreateRoom", req, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

// CreateToken mints a participant JWT. LiveKit token issuance is handled by
// the /twirp/livekit.RoomService/CreateToken endpoint when API key/secret are
// configured.
func (c *DefaultLiveKitClient) CreateToken(ctx context.Context, req CreateTokenRequest) (*CreateTokenResponse, error) {
	var out CreateTokenResponse
	if err := c.twirpPost(ctx, "CreateToken", req, &out); err != nil {
		return nil, err
	}
	if out.URL == "" {
		out.URL = c.baseURL
	}
	return &out, nil
}

// RemoveParticipant removes a participant from a room.
func (c *DefaultLiveKitClient) RemoveParticipant(ctx context.Context, req RemoveParticipantRequest) error {
	return c.twirpPost(ctx, "RemoveParticipant", req, nil)
}

// MutePublishedTrack mutes or unmutes a published track.
func (c *DefaultLiveKitClient) MutePublishedTrack(ctx context.Context, req MuteTrackRequest) error {
	return c.twirpPost(ctx, "MutePublishedTrack", req, nil)
}
