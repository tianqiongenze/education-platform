// Package health provides a unified /healthz handler for all
// education-platform Go services (D-109).
package health

import (
	"encoding/json"
	"net/http"
)

// HealthStatus represents the serialized health-check response.
type HealthStatus struct {
	Status  string            `json:"status"`
	Service string            `json:"service"`
	Version string            `json:"version,omitempty"`
	Checks  map[string]string `json:"checks,omitempty"`
}

// Handler returns a standard /healthz HTTP handler. It always responds with
// HTTP 200 and a JSON body describing the service. serviceName identifies the
// service (e.g. "ws-gateway"); version is the build version (may be "").
//
// The returned handler is safe for concurrent use: it carries only immutable
// configuration and writes to per-request ResponseWriters.
func Handler(serviceName, version string) http.HandlerFunc {
	status := HealthStatus{
		Status:  "UP",
		Service: serviceName,
		Version: version,
	}
	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		w.WriteHeader(http.StatusOK)
		_ = json.NewEncoder(w).Encode(status)
	}
}
