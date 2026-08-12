# syntax=docker/dockerfile:1.7
# D-115: Multi-stage Dockerfile for ws-gateway (Go WebSocket gateway).
#
# Service:  ws-gateway
# Module:   github.com/education-platform/ws-gateway  (main package: src/main.go)
# Port:     8081 (K8s Deployment maps 8081 -> container 8081)
# Health:   GET /healthz -> 200 "ok"  (defined in src/main.go)
#
# Build:
#   docker build -t harbor.edu.internal/education-platform/ws-gateway:1.0.0 \
#       services/ws-gateway
#
# Note: ws-gateway depends on the local replace directive for
# github.com/education-platform/ep-common-go (-> ../ep-common-go). The Docker
# build context is services/ws-gateway, so we vendor ep-common-go into the
# build by copying it from a sibling context. To keep the build self-contained,
# the builder stage clones ep-common-go is NOT needed here because go build
# resolves the replace via the local filesystem; the build context must include
# ../ep-common-go. See deploy/build/build-all-images.sh which sets the context
# to services/ and uses -f services/ws-gateway/Dockerfile so the replace path
# resolves.

ARG GO_VERSION=1.23
ARG ALPINE_VERSION=3.20

# ---------- builder ----------
FROM golang:${GO_VERSION}-alpine AS builder

# git + ca-certificates needed for module fetches; tzdata for log timestamps.
RUN apk add --no-cache git ca-certificates tzdata

WORKDIR /src

# Copy the ws-gateway module.
COPY ws-gateway/ ./ws-gateway/

# Copy the locally-replaced ep-common-go sibling module (go.mod replace target).
COPY ep-common-go/ ./ep-common-go/

WORKDIR /src/ws-gateway

# Tidy is intentionally NOT run (offline build). go.sum is committed.
# CGO_ENABLED=0 -> fully static binary, runs on scratch/alpine without libc.
# GOMODCACHE is cached via the bind mount below when BuildKit is enabled.
RUN --mount=type=cache,target=/go/pkg/mod \
    --mount=type=cache,target=/root/.cache/go-build \
    CGO_ENABLED=0 GOOS=linux GOARCH=amd64 \
    go build -trimpath -ldflags="-s -w" -o /out/ws-gateway ./src

# ---------- runtime ----------
FROM alpine:${ALPINE_VERSION}

# ca-certificates (outbound TLS to Redis/Pulsar), tzdata (logs), wget (healthcheck).
RUN apk add --no-cache ca-certificates tzdata wget && \
    addgroup -S app && adduser -S -G app app

# Non-root user for the runtime process.
USER app

COPY --from=builder /out/ws-gateway /usr/local/bin/ws-gateway

# ws-gateway reads PORT env (default 8080 in main.go; K8s sets PORT=8081).
ENV PORT=8081
EXPOSE 8081

# /healthz is registered in src/main.go (returns 200 "ok").
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD wget -qO- http://127.0.0.1:${PORT}/healthz || exit 1

ENTRYPOINT ["/usr/local/bin/ws-gateway"]
