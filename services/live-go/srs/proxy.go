// Package srs — proxy.go implements the WHIP/WHEP HTTP proxy to the SRS origin.
//
// D-187: the live-go service terminates WHIP (push) and WHEP (pull) on behalf
// of clients and forwards the SDP offer/answer exchange to the SRS origin that
// owns the stream. This keeps the SRS internal network address private and lets
// live-go enforce auth/rate-limit policy at the edge.
//
// WHIP flow (publisher → live-go → SRS):
//  1. Publisher POSTs an SDP offer to /whip/{roomId}
//  2. live-go resolves the owning origin via OriginRouter.GetOrigin (claim if
//     unclaimed via SelectOrigin)
//  3. live-go forwards the offer to SRS /rtc/v1/whip/?app=live&stream={roomId}
//  4. SRS returns an SDP answer; live-go relays it to the publisher
//
// WHEP flow (viewer → live-go → SRS) is the symmetric pull path using
// /rtc/v1/whep/.
package srs

import (
	"context"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

// ProxyConfig controls the HTTP client used to reach SRS origins.
type ProxyConfig struct {
	// Timeout is the per-request deadline for the SRS round-trip. WHIP/WHEP
	// SDP exchange is fast (<1s) but we leave headroom for a cold origin.
	Timeout time.Duration
}

// DefaultProxyConfig returns production defaults.
func DefaultProxyConfig() ProxyConfig {
	return ProxyConfig{Timeout: 10 * time.Second}
}

// Proxy forwards WHIP/WHEP SDP bodies to an SRS origin. It is safe for
// concurrent use: the embedded http.Client is goroutine-safe and immutable.
type Proxy struct {
	router *OriginRouter
	cfg    ProxyConfig
	client *http.Client
}

// NewProxy builds a Proxy bound to an OriginRouter.
func NewProxy(router *OriginRouter, cfg ProxyConfig) *Proxy {
	return &Proxy{
		router: router,
		cfg:    cfg,
		client: &http.Client{Timeout: cfg.Timeout},
	}
}

// resolveOrigin returns the origin host:port for the room, claiming one if no
// origin owns the stream yet.
func (p *Proxy) resolveOrigin(ctx context.Context, roomID string) (string, error) {
	origin, err := p.router.GetOrigin(ctx, roomID)
	if err != nil {
		return "", err
	}
	if origin != "" {
		return origin, nil
	}
	// Unclaimed — pick the least-loaded origin and claim it.
	node, err := p.router.SelectOrigin(ctx)
	if err != nil {
		return "", fmt.Errorf("srs proxy: select origin: %w", err)
	}
	claimed, err := p.router.ClaimStream(ctx, roomID, node)
	if err != nil && !strings.Contains(err.Error(), ErrAlreadyClaimed.Error()) {
		return "", fmt.Errorf("srs proxy: claim stream: %w", err)
	}
	if !claimed {
		// Race lost: another replica claimed first. Use that origin.
		origin, err = p.router.GetOrigin(ctx, roomID)
		if err != nil {
			return "", err
		}
		if origin == "" {
			return "", fmt.Errorf("srs proxy: stream %s claimed but no owner resolved", roomID)
		}
		return origin, nil
	}
	return node, nil
}

// forwardSDP is the shared WHIP/WHEP relay. method is "whip" or "whep".
func (p *Proxy) forwardSDP(w http.ResponseWriter, r *http.Request, roomID, method string) {
	if roomID == "" {
		http.Error(w, "missing roomId", http.StatusBadRequest)
		return
	}
	body, err := io.ReadAll(io.LimitReader(r.Body, 1<<20)) // 1MB SDP cap
	if err != nil {
		http.Error(w, "read body: "+err.Error(), http.StatusBadRequest)
		return
	}
	defer r.Body.Close()

	ctx, cancel := context.WithTimeout(r.Context(), p.cfg.Timeout)
	defer cancel()

	origin, err := p.resolveOrigin(ctx, roomID)
	if err != nil {
		http.Error(w, "resolve origin: "+err.Error(), http.StatusServiceUnavailable)
		return
	}

	target := fmt.Sprintf("http://%s/rtc/v1/%s/?app=live&stream=%s", origin, method, roomID)
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, target, strings.NewReader(string(body)))
	if err != nil {
		http.Error(w, "build request: "+err.Error(), http.StatusInternalServerError)
		return
	}
	req.Header.Set("Content-Type", "application/sdp")

	resp, err := p.client.Do(req)
	if err != nil {
		http.Error(w, "srs request: "+err.Error(), http.StatusBadGateway)
		return
	}
	defer resp.Body.Close()

	w.Header().Set("Content-Type", "application/sdp")
	w.WriteHeader(resp.StatusCode)
	_, _ = io.Copy(w, resp.Body)
}

// HandleWHIP is the /whip/{roomId} handler.
func (p *Proxy) HandleWHIP(w http.ResponseWriter, r *http.Request) {
	roomID := r.PathValue("roomId")
	p.forwardSDP(w, r, roomID, "whip")
}

// HandleWHEP is the /whep/{roomId} handler.
func (p *Proxy) HandleWHEP(w http.ResponseWriter, r *http.Request) {
	roomID := r.PathValue("roomId")
	p.forwardSDP(w, r, roomID, "whep")
}

// IngestURL returns the WHIP push URL exposed by live-go for a room.
func IngestURL(roomID string) string {
	return "/whip/" + roomID
}

// PlayURL returns the WHEP pull URL exposed by live-go for a room.
func PlayURL(roomID string) string {
	return "/whep/" + roomID
}
