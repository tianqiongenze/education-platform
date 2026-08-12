package yjs

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

// D-186: handler.go tests

// newTestRequest creates an *http.Request for testing.
func newTestRequest(path, token string) *http.Request {
	req := httptest.NewRequest("GET", path, nil)
	if token != "" {
		q := req.URL.Query()
		q.Set("token", token)
		req.URL.RawQuery = q.Encode()
	}
	return req
}

// testRecorder is a minimal http.ResponseWriter that records the status code
// and body. It does NOT support Hijack (so WebSocket upgrade will fail, which
// is fine for testing the pre-upgrade error paths).
type testRecorder struct {
	header http.Header
	code   int
	body   []byte
}

func newTestRecorder() *testRecorder {
	return &testRecorder{header: http.Header{}, code: 200}
}

func (r *testRecorder) Header() http.Header        { return r.header }
func (r *testRecorder) WriteHeader(code int)        { r.code = code }
func (r *testRecorder) Write(b []byte) (int, error) { r.body = append(r.body, b...); return len(b), nil }

func TestExtractSessionID(t *testing.T) {
	tests := []struct {
		path     string
		expected string
	}{
		{"/ws/ide/session-123", "session-123"},
		{"/ws/ide/session-123/", "session-123"},
		{"/ws/ide/abc-def-456", "abc-def-456"},
		{"/ws/ide/session-123?token=xyz", "session-123"},
		{"/ws/ide/", ""},
		{"/ws/ide", ""},
		{"/ws/other/session-1", ""},
		{"/api/v1/ide/sessions", ""},
		{"", ""},
	}
	for _, tt := range tests {
		got := extractSessionID(tt.path)
		if got != tt.expected {
			t.Fatalf("extractSessionID(%q) = %q, want %q", tt.path, got, tt.expected)
		}
	}
}

func TestIsWebSocketUpgrade(t *testing.T) {
	// Test the real isWebSocketUpgrade with http.Header values
	h := http.Header{}
	h.Set("Upgrade", "websocket")
	h.Set("Connection", "Upgrade")
	if !isWebSocketUpgrade(h) {
		t.Fatal("expected true for valid websocket upgrade headers")
	}

	h2 := http.Header{}
	h2.Set("Upgrade", "h2c")
	if isWebSocketUpgrade(h2) {
		t.Fatal("expected false for non-websocket upgrade")
	}

	h3 := http.Header{}
	if isWebSocketUpgrade(h3) {
		t.Fatal("expected false for empty headers")
	}
}

func TestNewHandler(t *testing.T) {
	h := NewHandler(nil, nil, true)
	if h == nil {
		t.Fatal("NewHandler returned nil")
	}
	if h.Registry() == nil {
		t.Fatal("Registry should not be nil (auto-created)")
	}
	if h.Registry().Count() != 0 {
		t.Fatalf("expected 0 rooms, got %d", h.Registry().Count())
	}
}

func TestNewHandlerWithExistingRegistry(t *testing.T) {
	reg := NewRoomRegistry()
	reg.GetOrCreate("pre-existing")

	h := NewHandler(reg, nil, true)
	if h.Registry() != reg {
		t.Fatal("NewHandler should use the provided registry")
	}
	if h.Registry().Count() != 1 {
		t.Fatalf("expected 1 pre-existing room, got %d", h.Registry().Count())
	}
}

func TestNextYjsConnID(t *testing.T) {
	id1 := nextYjsConnID()
	id2 := nextYjsConnID()
	if id1 == "" {
		t.Fatal("conn id should not be empty")
	}
	if id1 == id2 {
		t.Fatal("consecutive conn ids should be unique")
	}
}

func TestHandlerServeHTTPMissingSessionID(t *testing.T) {
	h := NewHandler(nil, nil, true)

	// Build a request without session id in path
	req := newTestRequest("/ws/ide/", "")
	rec := newTestRecorder()

	h.ServeHTTP(rec, req)

	if rec.code != 400 {
		t.Fatalf("expected 400 for missing session id, got %d", rec.code)
	}
}

func TestHandlerServeHTTPNoValidatorNoAnonymous(t *testing.T) {
	h := NewHandler(nil, nil, false)

	req := newTestRequest("/ws/ide/session-1", "")
	rec := newTestRecorder()

	h.ServeHTTP(rec, req)

	if rec.code != 401 {
		t.Fatalf("expected 401 without validator and anonymous disabled, got %d", rec.code)
	}
}

func TestHandlerServeHTTPNotWebSocket(t *testing.T) {
	h := NewHandler(nil, nil, true)

	req := newTestRequest("/ws/ide/session-1", "")
	// no Upgrade / Connection headers → not a websocket request
	rec := newTestRecorder()

	h.ServeHTTP(rec, req)

	if rec.code != 400 {
		t.Fatalf("expected 400 for non-websocket request, got %d", rec.code)
	}
}
