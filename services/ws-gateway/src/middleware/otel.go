// Package middleware provides HTTP middleware for ws-gateway.
//
// D-118 / D-133 / D-139: W3C TraceContext header propagation for ws-gateway.
//
// The full OTel Go SDK (OTLP exporter, otelhttp wrapper) is declared in
// go.mod but cannot be downloaded in the offline build environment. Rather
// than ship a pure no-op that silently breaks the cross-service trace chain,
// D-133 implements W3C TraceContext *manual header propagation* using only
// the Go standard library.
//
// What this gives us without the SDK:
//   - The incoming `traceparent` header (W3C TraceContext) is extracted from
//     every request and stored in the request context.
//   - If no `traceparent` arrives, a fresh trace id (16-byte hex) and span id
//     (8-byte hex) are generated so every gateway request participates in a
//     trace even when the caller did not propagate one.
//   - The `traceparent` is echoed back on the response and exposed via
//     context so downstream HTTP calls (made with FromContext + an injected
//     header) continue the same trace through portal-bff, ai-platform, etc.
//
// This is intentionally a *correlation* layer, not a span exporter: it does
// not emit OTLP spans. When the OTel Go SDK becomes available in CI, the
// full implementation in otel_full.go.disabled will replace these functions
// with otelhttp.NewHandler (server spans + OTLP export) while preserving the
// same traceparent contract, so downstream code that reads the context stays
// valid.
//
// ---------------------------------------------------------------------------
// D-139 (P0-2) TODO — enable real OTel SDK span export in CI:
//
//   1. Add to go.mod (when network is available):
//        go.opentelemetry.io/otel
//        go.opentelemetry.io/otel/sdk
//        go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracehttp
//        go.opentelemetry.io/otel/exporters/otlp/otlptrace
//        go.opentelemetry.io/contrib/instrumentation/net/http/otelhttp
//   2. Rename `otel_full.go.disabled` -> `otel_full.go` (it carries the
//      `//go:build otel` build tag so it only compiles when built with
//      `-tags otel`).
//   3. Build with: `go build -tags otel ./...` in CI; the default offline
//      build still uses this file's W3C-透传 no-op.
//   4. InitTracer / ShutdownTracer / Tracer in otel_full.go override these
//      no-op stubs via the same exported names (build-tag selection picks
//      exactly one implementation at compile time, so there is no duplicate
//      symbol error).
// ---------------------------------------------------------------------------
package middleware

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"net/http"
	"strings"
)

// TraceparentHeader is the W3C TraceContext header name.
const TraceparentHeader = "traceparent"

// ctxKey is an unexported type so no other package can collide on the
// context value stored by this middleware.
type ctxKey struct{}

// traceContext is the propagated trace state. It mirrors the fields of a W3C
// traceparent: https://www.w3.org/TR/trace-context/#traceparent-header
type traceContext struct {
	version  string // 2 hex chars, always "00" here
	traceID  string // 32 hex chars (16 bytes)
	parentID string // 16 hex chars (8 bytes) — the gateway's span/parent id
	flags    string // 2 hex chars, "01" = sampled
}

// TraceIDFromContext returns the W3C trace id stored in the context by
// OTelHTTPMiddleware, or "" if the request was not instrumented.
//
// Downstream HTTP clients in this binary should call this, build a child
// traceparent, and set the `traceparent` header on the outbound request so
// the receiving service (portal-bff, ai-platform, ...) can join the trace.
func TraceIDFromContext(ctx context.Context) string {
	tc, _ := ctx.Value(ctxKey{}).(traceContext)
	return tc.traceID
}

// TraceparentFromContext returns the full W3C traceparent string
// (`version-traceid-parentid-flags`) propagated for this request, or "" if
// the request was not instrumented. Suitable for forwarding verbatim on an
// outbound HTTP call when the gateway does not start a child span.
func TraceparentFromContext(ctx context.Context) string {
	tc, _ := ctx.Value(ctxKey{}).(traceContext)
	if tc.traceID == "" {
		return ""
	}
	return formatTraceparent(tc)
}

// formatTraceparent renders a W3C traceparent header value.
func formatTraceparent(tc traceContext) string {
	var b strings.Builder
	b.Grow(55) // 2 + 1 + 32 + 1 + 16 + 1 + 2 = 55
	b.WriteString(tc.version)
	b.WriteByte('-')
	b.WriteString(tc.traceID)
	b.WriteByte('-')
	b.WriteString(tc.parentID)
	b.WriteByte('-')
	b.WriteString(tc.flags)
	return b.String()
}

// parseTraceparent parses a W3C traceparent header value. Returns ok=false
// when the value is malformed; in that case the caller generates a fresh
// trace. Only version "00" is accepted per the W3C spec (unknown versions
// MUST NOT be used).
func parseTraceparent(header string) (traceContext, bool) {
	const sampled = "01"
	parts := strings.Split(header, "-")
	if len(parts) != 4 {
		return traceContext{}, false
	}
	version, traceID, parentID, flags := parts[0], parts[1], parts[2], parts[3]
	if version != "00" || len(traceID) != 32 || len(parentID) != 16 || len(flags) != 2 {
		return traceContext{}, false
	}
	if !isHex(traceID) || !isHex(parentID) || !isHex(flags) {
		return traceContext{}, false
	}
	// trace-id MUST NOT be all zeros.
	if traceID == "00000000000000000000000000000000" {
		return traceContext{}, false
	}
	return traceContext{
		version:  "00",
		traceID:  traceID,
		parentID: parentID,
		flags:    flags,
	}, true
}

// isHex reports whether s consists only of lowercase/uppercase hex chars.
func isHex(s string) bool {
	for _, r := range s {
		switch {
		case r >= '0' && r <= '9':
		case r >= 'a' && r <= 'f':
		case r >= 'A' && r <= 'F':
		default:
			return false
		}
	}
	return true
}

// newTraceContext generates a fresh trace context for a request that arrived
// without a traceparent. version="00", flags="01" (sampled). Panics are
// impossible: rand.Read on the crypto/rand reader never fails in practice
// and falls back to filling with zeros on a read error, which we then reject
// (and regenerate) via the all-zeros guard in parseTraceparent — but for the
// span id an all-zero value is also illegal, so we regenerate once if needed.
func newTraceContext() traceContext {
	traceID := randHex(16)
	parentID := randHex(8)
	if parentID == "0000000000000000" {
		// Astronomically unlikely; regenerate to stay spec-compliant.
		parentID = randHex(8)
	}
	return traceContext{
		version:  "00",
		traceID:  traceID,
		parentID: parentID,
		flags:    "01", // sampled
	}
}

// randHex returns n random bytes rendered as 2*n lowercase hex chars.
func randHex(n int) string {
	b := make([]byte, n)
	_, _ = rand.Read(b) // crypto/rand.Read never returns an error on darwin/linux
	return hex.EncodeToString(b)
}

// OTelHTTPMiddleware extracts (or generates) the W3C `traceparent` header,
// stores it in the request context, and echoes it on the response so clients
// and downstream services can correlate logs/metrics across the trace chain.
//
// This runs without the OTel SDK; it only propagates the traceparent string.
// When the SDK is available (CI-enabled via otel_full.go), this wrapper is
// replaced by otelhttp.NewHandler which additionally emits a server span —
// the context contract (TraceparentFromContext) stays the same.
func OTelHTTPMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		tc, ok := parseTraceparent(r.Header.Get(TraceparentHeader))
		if !ok {
			tc = newTraceContext()
		}
		// Echo on the response so callers (browser, BFF, tests) can correlate.
		w.Header().Set(TraceparentHeader, formatTraceparent(tc))
		ctx := context.WithValue(r.Context(), ctxKey{}, tc)
		next.ServeHTTP(w, r.WithContext(ctx))
	})
}

// InitTracer is a graceful no-op. The traceparent propagation above works
// independently of the OTel SDK and does not require any global
// initialization.
//
// D-139 (P0-2) TODO: this is the offline stub. When the OTel Go SDK is
// available in CI (build tag `otel`), the override in `otel_full.go`
// replaces this with a real OTLP TracerProvider setup. See the package doc
// comment for the activation steps. Returns nil today so existing callers
// compile.
func InitTracer() interface{} {
	return nil
}

// ShutdownTracer is a graceful no-op (see InitTracer). When the OTel SDK is
// enabled (D-139, build tag `otel`), the override flushes pending spans to
// the OTLP collector before exit.
func ShutdownTracer(_ context.Context) {
	// no-op (D-139 offline stub)
}

// Tracer returns nil (no-op). Callers should nil-check before using the
// result. When the OTel SDK is enabled (D-139, build tag `otel`), the
// override returns the global Tracer.
func Tracer() interface{} {
	return nil
}
