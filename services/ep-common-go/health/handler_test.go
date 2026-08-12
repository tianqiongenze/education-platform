package health

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestHandlerReturnsOK(t *testing.T) {
	h := Handler("ws-gateway", "1.2.3")
	w := httptest.NewRecorder()
	h(w, httptest.NewRequest(http.MethodGet, "/healthz", nil))

	if w.Code != http.StatusOK {
		t.Fatalf("status: want 200 got %d", w.Code)
	}
	if ct := w.Header().Get("Content-Type"); ct != "application/json; charset=utf-8" {
		t.Fatalf("content-type: got %q", ct)
	}
	var s HealthStatus
	if err := json.Unmarshal(w.Body.Bytes(), &s); err != nil {
		t.Fatalf("decode: %v (body=%q)", err, w.Body.String())
	}
	if s.Status != "UP" {
		t.Errorf("status: want UP got %s", s.Status)
	}
	if s.Service != "ws-gateway" {
		t.Errorf("service: want ws-gateway got %s", s.Service)
	}
	if s.Version != "1.2.3" {
		t.Errorf("version: want 1.2.3 got %s", s.Version)
	}
}

func TestHandlerOmitsEmptyVersion(t *testing.T) {
	h := Handler("svc", "")
	w := httptest.NewRecorder()
	h(w, httptest.NewRequest(http.MethodGet, "/healthz", nil))

	var raw map[string]json.RawMessage
	if err := json.Unmarshal(w.Body.Bytes(), &raw); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if _, present := raw["version"]; present {
		t.Errorf("version should be omitted when empty, got %s", raw["version"])
	}
}

func TestHandlerAlways200(t *testing.T) {
	// Health checks must report 200 even when version is empty.
	cases := []struct{ name, svc, ver string }{
		{"empty", "", ""},
		{"svc-only", "portal-bff", ""},
		{"full", "sp01-user", "0.0.1"},
	}
	for _, c := range cases {
		h := Handler(c.svc, c.ver)
		w := httptest.NewRecorder()
		h(w, httptest.NewRequest(http.MethodGet, "/healthz", nil))
		if w.Code != http.StatusOK {
			t.Errorf("%s: want 200 got %d", c.name, w.Code)
		}
	}
}
