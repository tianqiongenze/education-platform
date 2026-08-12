// Package httpresp provides the unified JSON response format for all
// education-platform Go services (D-109).
//
// Successful handlers should call WriteSuccess; error paths should call
// WriteError. Both set the appropriate Content-Type and HTTP status code.
package httpresp

import (
	"encoding/json"
	"net/http"
)

// SuccessResponse is the standard envelope for successful API responses.
// Data is omitted from the JSON output entirely when nil.
type SuccessResponse struct {
	Code    int         `json:"code"`
	Message string      `json:"message"`
	Data    interface{} `json:"data,omitempty"`
}

// ErrorResponse is the standard envelope for error API responses.
// TraceID is optional and omitted when empty.
type ErrorResponse struct {
	Code    string `json:"code"`
	Message string `json:"message"`
	TraceID string `json:"traceId,omitempty"`
}

// WriteSuccess writes a 200 SuccessResponse with the given data payload.
// The payload is rendered under the "data" key; nil data is omitted.
func WriteSuccess(w http.ResponseWriter, data interface{}) {
	WriteSuccessStatus(w, http.StatusOK, data)
}

// WriteSuccessStatus writes a SuccessResponse with a caller-chosen status
// code (e.g. 201 for creates). Message is set to "OK".
func WriteSuccessStatus(w http.ResponseWriter, statusCode int, data interface{}) {
	resp := SuccessResponse{
		Code:    statusCode,
		Message: "OK",
		Data:    data,
	}
	writeJSON(w, statusCode, resp)
}

// WriteError writes an ErrorResponse with the given HTTP status code,
// machine-readable error code, and human-readable message.
func WriteError(w http.ResponseWriter, statusCode int, code, message string) {
	WriteErrorTrace(w, statusCode, code, message, "")
}

// WriteErrorTrace is WriteError with an optional traceId for correlation.
func WriteErrorTrace(w http.ResponseWriter, statusCode int, code, message, traceID string) {
	resp := ErrorResponse{
		Code:    code,
		Message: message,
		TraceID: traceID,
	}
	writeJSON(w, statusCode, resp)
}

func writeJSON(w http.ResponseWriter, statusCode int, payload interface{}) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(statusCode)
	// A marshal/write failure at this point cannot meaningfully be reported
	// to the client (headers already sent); ignore it.
	_ = json.NewEncoder(w).Encode(payload)
}
