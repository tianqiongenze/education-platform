// Package api — handler.go implements the REST endpoints for live-go (D-187).
//
// Endpoints:
//   POST /api/v1/live/rooms                      create a live room
//   GET  /api/v1/live/rooms/{roomId}/ingest       get WHIP ingest URL
//   GET  /api/v1/live/rooms/{roomId}/play         get WHEP/HLS play URL
//   POST /api/v1/live/rooms/{roomId}/interact     mint a LiveKit token
//
// The handler depends on the SRS OriginRouter/Proxy and the LiveKit SfuManager.
// All responses use the shared httpresp envelope.
package api

import (
	"encoding/json"
	"net/http"
	"strconv"

	commonhealth "github.com/education-platform/ep-common-go/health"
	"github.com/education-platform/ep-common-go/httpresp"
	"github.com/education-platform/live-go/livekit"
	"github.com/education-platform/live-go/srs"
)

// Handler bundles the REST dependencies.
type Handler struct {
	sfu *livekit.SfuManager
	// health is embedded so the same mux can serve /healthz without an extra
	// handler indirection.
	health http.HandlerFunc
}

// NewHandler builds the REST handler.
func NewHandler(sfu *livekit.SfuManager) *Handler {
	return &Handler{
		sfu:    sfu,
		health: commonhealth.Handler("live-go", "1.0.0"),
	}
}

// Register wires the handler onto a *http.ServeMux. Routes use Go 1.22+
// method+path patterns so {roomId} is available via r.PathValue.
func (h *Handler) Register(mux *http.ServeMux) {
	mux.HandleFunc("GET /healthz", h.health)
	mux.HandleFunc("POST /api/v1/live/rooms", h.createRoom)
	mux.HandleFunc("GET /api/v1/live/rooms/{roomId}/ingest", h.getIngest)
	mux.HandleFunc("GET /api/v1/live/rooms/{roomId}/play", h.getPlay)
	mux.HandleFunc("POST /api/v1/live/rooms/{roomId}/interact", h.interact)
}

// createRoom handles POST /api/v1/live/rooms.
func (h *Handler) createRoom(w http.ResponseWriter, r *http.Request) {
	var cmd CreateRoomCmd
	if err := json.NewDecoder(r.Body).Decode(&cmd); err != nil {
		httpresp.WriteError(w, http.StatusBadRequest, "BAD_REQUEST", "invalid JSON body")
		return
	}
	if cmd.RoomID == "" {
		httpresp.WriteError(w, http.StatusBadRequest, "BAD_REQUEST", "roomId is required")
		return
	}
	if cmd.Mode == "" {
		cmd.Mode = ModeBroadcast
	}
	if cmd.Mode != ModeInteractive && cmd.Mode != ModeBroadcast {
		httpresp.WriteError(w, http.StatusBadRequest, "BAD_REQUEST", "mode must be INTERACTIVE or BROADCAST")
		return
	}

	// For interactive rooms, provision an SFU room eagerly so the client can
	// connect immediately after this call.
	var sfuRoomID string
	if cmd.Mode == ModeInteractive && h.sfu.Configured() {
		info, err := h.sfu.CreateSfuRoom(r.Context(), cmd.RoomID, cmd.ViewerLimit)
		if err != nil {
			httpresp.WriteError(w, http.StatusServiceUnavailable, "SFU_UNAVAILABLE", err.Error())
			return
		}
		sfuRoomID = info.SID
	}

	resp := LiveRoomResponse{
		RoomID:      cmd.RoomID,
		TenantID:    cmd.TenantID,
		UserID:      cmd.UserID,
		Mode:        cmd.Mode,
		Status:      StatusCreated,
		ViewerLimit: cmd.ViewerLimit,
		SFURoomID:   sfuRoomID,
		IngestURL:   srs.IngestURL(cmd.RoomID),
		PlayURL:     srs.PlayURL(cmd.RoomID),
	}
	httpresp.WriteSuccessStatus(w, http.StatusCreated, resp)
}

// getIngest handles GET /api/v1/live/rooms/{roomId}/ingest.
func (h *Handler) getIngest(w http.ResponseWriter, r *http.Request) {
	roomID := r.PathValue("roomId")
	if roomID == "" {
		httpresp.WriteError(w, http.StatusBadRequest, "BAD_REQUEST", "roomId is required")
		return
	}
	httpresp.WriteSuccess(w, IngestResponse{
		RoomID:    roomID,
		IngestURL: srs.IngestURL(roomID),
		Protocol:  "whip",
	})
}

// getPlay handles GET /api/v1/live/rooms/{roomId}/play.
func (h *Handler) getPlay(w http.ResponseWriter, r *http.Request) {
	roomID := r.PathValue("roomId")
	if roomID == "" {
		httpresp.WriteError(w, http.StatusBadRequest, "BAD_REQUEST", "roomId is required")
		return
	}
	protocol := r.URL.Query().Get("protocol")
	if protocol == "" {
		protocol = "whep"
	}
	httpresp.WriteSuccess(w, PlayResponse{
		RoomID:   roomID,
		PlayURL:  srs.PlayURL(roomID),
		Protocol: protocol,
	})
}

// interact handles POST /api/v1/live/rooms/{roomId}/interact.
func (h *Handler) interact(w http.ResponseWriter, r *http.Request) {
	roomID := r.PathValue("roomId")
	if roomID == "" {
		httpresp.WriteError(w, http.StatusBadRequest, "BAD_REQUEST", "roomId is required")
		return
	}
	var req InteractRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		httpresp.WriteError(w, http.StatusBadRequest, "BAD_REQUEST", "invalid JSON body")
		return
	}
	if req.UserID == 0 {
		httpresp.WriteError(w, http.StatusBadRequest, "BAD_REQUEST", "userId is required")
		return
	}
	if req.Role == "" {
		req.Role = "subscriber"
	}
	if !h.sfu.Configured() {
		httpresp.WriteError(w, http.StatusServiceUnavailable, "SFU_UNAVAILABLE",
			"interactive mode requires LiveKit (LIVEKIT_URL not configured)")
		return
	}
	// Ensure the SFU room exists.
	if _, err := h.sfu.CreateSfuRoom(r.Context(), roomID, 0); err != nil {
		httpresp.WriteError(w, http.StatusServiceUnavailable, "SFU_UNAVAILABLE", err.Error())
		return
	}
	tokenResp, err := h.sfu.GenerateToken(r.Context(), roomID, strconv.FormatInt(req.UserID, 10), req.Role)
	if err != nil {
		httpresp.WriteError(w, http.StatusServiceUnavailable, "SFU_TOKEN_FAILED", err.Error())
		return
	}
	httpresp.WriteSuccess(w, InteractResponse{
		RoomID: roomID,
		Token:  tokenResp.Token,
		URL:    tokenResp.URL,
	})
}
