// Package api holds the REST/WebSocket types and handlers for the live-go
// service (D-187).
//
// types.go defines the request/response structs exchanged with the frontend and
// the SRS/LiveKit orchestrator. All structs are JSON-serializable and carry
// json tags so they may be embedded directly in the standard httpresp envelope.
package api

// LiveMode controls whether a room uses SFU-based interactive conferencing
// (INTERACTIVE — LiveKit) or one-to-many broadcast (BROADCAST — SRS WHIP/WHEP).
type LiveMode string

const (
	ModeInteractive LiveMode = "INTERACTIVE"
	ModeBroadcast   LiveMode = "BROADCAST"
)

// LiveStatus mirrors the grading-service LiveRoom aggregate status so the Go
// service and the Java aggregate stay in sync. The Go side never mutates these
// values directly; the Java service owns the lifecycle.
type LiveStatus string

const (
	StatusCreated    LiveStatus = "CREATED"
	StatusPreparing  LiveStatus = "PREPARING"
	StatusLive       LiveStatus = "LIVE"
	StatusPaused     LiveStatus = "PAUSED"
	StatusEnded      LiveStatus = "ENDED"
	StatusArchived   LiveStatus = "ARCHIVED"
)

// CreateRoomCmd is the POST /api/v1/live/rooms request body.
type CreateRoomCmd struct {
	RoomID      string   `json:"roomId"`
	TenantID    int64    `json:"tenantId"`
	UserID      int64    `json:"userId"`
	Mode        LiveMode `json:"mode"`
	ViewerLimit int      `json:"viewerLimit"`
}

// LiveRoomResponse is the response for create/get room endpoints.
type LiveRoomResponse struct {
	RoomID      string     `json:"roomId"`
	TenantID    int64      `json:"tenantId"`
	UserID      int64      `json:"userId"`
	Mode        LiveMode   `json:"mode"`
	Status      LiveStatus `json:"status"`
	ViewerLimit int        `json:"viewerLimit"`
	IngestURL   string     `json:"ingestUrl,omitempty"`
	PlayURL     string     `json:"playUrl,omitempty"`
	SFURoomID   string     `json:"sfuRoomId,omitempty"`
}

// IngestResponse is returned by GET /api/v1/live/rooms/{roomId}/ingest.
type IngestResponse struct {
	RoomID    string `json:"roomId"`
	IngestURL string `json:"ingestUrl"`
	Protocol  string `json:"protocol"` // "whip"
}

// PlayResponse is returned by GET /api/v1/live/rooms/{roomId}/play.
type PlayResponse struct {
	RoomID   string `json:"roomId"`
	PlayURL  string `json:"playUrl"`
	Protocol string `json:"protocol"` // "whep" or "hls"
}

// InteractRequest is the body of POST /api/v1/live/rooms/{roomId}/interact.
type InteractRequest struct {
	UserID int64  `json:"userId"`
	Role   string `json:"role"` // "publisher" | "subscriber"
}

// InteractResponse returns the LiveKit JWT for an interactive viewer/publisher.
type InteractResponse struct {
	RoomID string `json:"roomId"`
	Token  string `json:"token"`
	URL    string `json:"url"`
}

// DanmakuMessage is the wire format for a single chat/danmaku message, both
// for inbound WebSocket reads and outbound batched broadcasts.
type DanmakuMessage struct {
	UserID  string `json:"userId"`
	Message string `json:"message"`
	TS      int64  `json:"ts"`
}

// DanmakuBatch is a batch of DanmakuMessage broadcast every BatchWindow.
type DanmakuBatch struct {
	RoomID   string           `json:"roomId"`
	Messages []DanmakuMessage `json:"messages"`
}
