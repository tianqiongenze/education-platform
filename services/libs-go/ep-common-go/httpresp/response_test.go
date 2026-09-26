package httpresp

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

func decode(t *testing.T, w *httptest.ResponseRecorder) map[string]interface{} {
	t.Helper()
	var m map[string]interface{}
	if err := json.Unmarshal(w.Body.Bytes(), &m); err != nil {
		t.Fatalf("decode body: %v (body=%q)", err, w.Body.String())
	}
	return m
}

func TestWriteSuccess(t *testing.T) {
	w := httptest.NewRecorder()
	WriteSuccess(w, map[string]string{"hello": "world"})

	if w.Code != http.StatusOK {
		t.Fatalf("status: want 200 got %d", w.Code)
	}
	if ct := w.Header().Get("Content-Type"); ct != "application/json; charset=utf-8" {
		t.Fatalf("content-type: got %q", ct)
	}
	m := decode(t, w)
	if m["message"] != "OK" {
		t.Errorf("message: want OK got %v", m["message"])
	}
	if code, _ := m["code"].(float64); code != 200 {
		t.Errorf("code: want 200 got %v", m["code"])
	}
	data, ok := m["data"].(map[string]interface{})
	if !ok {
		t.Fatalf("data missing or wrong type: %v", m["data"])
	}
	if data["hello"] != "world" {
		t.Errorf("data.hello: want world got %v", data["hello"])
	}
}

func TestWriteSuccessOmitsNilData(t *testing.T) {
	w := httptest.NewRecorder()
	WriteSuccess(w, nil)
	m := decode(t, w)
	if _, present := m["data"]; present {
		t.Errorf("data should be omitted when nil, got %v", m["data"])
	}
}

func TestWriteSuccessStatus(t *testing.T) {
	w := httptest.NewRecorder()
	WriteSuccessStatus(w, http.StatusCreated, map[string]int{"id": 1})
	if w.Code != http.StatusCreated {
		t.Fatalf("status: want 201 got %d", w.Code)
	}
	m := decode(t, w)
	if code, _ := m["code"].(float64); code != 201 {
		t.Errorf("code: want 201 got %v", m["code"])
	}
}

func TestWriteError(t *testing.T) {
	w := httptest.NewRecorder()
	WriteError(w, http.StatusBadRequest, "BAD_REQUEST", "missing field")

	if w.Code != http.StatusBadRequest {
		t.Fatalf("status: want 400 got %d", w.Code)
	}
	m := decode(t, w)
	if m["code"] != "BAD_REQUEST" {
		t.Errorf("code: want BAD_REQUEST got %v", m["code"])
	}
	if m["message"] != "missing field" {
		t.Errorf("message: got %v", m["message"])
	}
	// traceId should be omitted when empty.
	if _, present := m["traceId"]; present {
		t.Errorf("traceId should be omitted when empty, got %v", m["traceId"])
	}
}

func TestWriteErrorTrace(t *testing.T) {
	w := httptest.NewRecorder()
	WriteErrorTrace(w, http.StatusInternalServerError, "INTERNAL", "boom", "abc-123")

	if w.Code != http.StatusInternalServerError {
		t.Fatalf("status: want 500 got %d", w.Code)
	}
	m := decode(t, w)
	if m["traceId"] != "abc-123" {
		t.Errorf("traceId: want abc-123 got %v", m["traceId"])
	}
}

func TestWriteSuccessContentTypeHeader(t *testing.T) {
	w := httptest.NewRecorder()
	WriteSuccess(w, nil)
	if ct := w.Header().Get("Content-Type"); ct == "" {
		t.Fatal("Content-Type header should be set")
	}
}
