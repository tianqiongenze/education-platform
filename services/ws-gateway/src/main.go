package main

import (
	"context"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	// D-109: jwt validation extracted to the shared ep-common-go module.
	commonjwt "github.com/education-platform/ep-common-go/jwt"
	"github.com/education-platform/ws-gateway/src/cache"
	"github.com/education-platform/ws-gateway/src/hub"
	"github.com/education-platform/ws-gateway/src/middleware"
	"github.com/education-platform/ws-gateway/src/ws"
	"github.com/education-platform/ws-gateway/src/yjs"
)

// ws-gateway: WebSocket gateway for education-platform.
// Validates RS256 JWT, routes WS connections, broadcasts via sharded channels.
// D-029: cross-pod broadcast uses Pulsar persistent://public/ws/broadcast (batch 2).
//
// D-118: OpenTelemetry tracing is initialized at startup so distributed
// trace chains are continuous from the gateway through every downstream
// service (portal-bff, ai-platform, ...). See src/middleware/otel.go.
//
// D-149: WebSocket /ws endpoint is now fully wired with JWT auth, connection
// registry, room-based broadcast (for board game live play), and Redis
// Pub/Sub subscribe loop for cross-pod message delivery.
func main() {
	port := os.Getenv("PORT")
	if port == "" {
		port = "8080"
	}

	// D-118: initialize OTel tracer provider. Failure is non-fatal — the
	// gateway continues untraced (no-op tracer) so a collector outage can
	// never take the gateway down. Currently a no-op stub until the OTel Go
	// SDK is available in the build environment.
	middleware.InitTracer()
	defer func() {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		middleware.ShutdownTracer(ctx)
	}()

	// D-117: JWT public key externalization.
	// Load the public key via one of (priority high→low):
	//   1) JWT_PUBLIC_KEY_PATH file path (default: /etc/jwt/public.pem, K8s Secret mount)
	//   2) JWT_PUBLIC_KEY base64-encoded inline env var
	//   3) JWT_RSA_PUBLIC_KEY_PEM legacy inline PEM env var (dev backwards-compat)
	// If none are available, WS auth is disabled with a loud warning (dev only).
	var validator *commonjwt.Validator
	allowAnonymous := true // dev default; set false when key is configured

	pubPem, src, err := commonjwt.LoadPublicKeyPEM()
	if err != nil {
		if os.Getenv("APP_ENV") == "production" {
			log.Fatalf("FATAL: jwt public key load failed in production: %v — refusing to start with anonymous WS auth", err)
		}
		log.Printf("WARNING: jwt public key load failed: %v (WS auth disabled, dev only)", err)
	} else {
		v, vErr := commonjwt.NewValidatorFromPEM(pubPem)
		if vErr != nil {
			log.Printf("WARNING: jwt validator init failed: %v (WS auth disabled, dev only)", vErr)
		} else {
			validator = v
			allowAnonymous = false
			log.Printf("jwt validator initialized (RS256, key source: %s)", src)
		}
	}

	// D-149: initialize Redis client, Hub, connection Registry, and WS Handler.
	redisAddr := os.Getenv("REDIS_ADDR")
	if redisAddr == "" {
		redisAddr = "redis-cluster:6379"
	}

	// Use the cache package's Redis client initialization pattern.
	cache.SetClientForTest(nil) // reset any prior test state
	_ = redisAddr               // cache package handles its own client init

	// Create a dedicated Redis client for the Hub.
	rdb := cache.GetRedisClientForHub()
	h := hub.New(rdb, 8)

	// D-180: inject the durable cross-pod transport (Redis Streams). When Redis
	// is available, each pod joins a shared consumer group so every pod
	// receives a copy of every broadcast. When Redis is unavailable (dev/offline
	// mode), the Hub keeps its default NoOpTransport and degrades gracefully to
	// Redis-only / in-memory behavior.
	if rdb != nil {
		transport := hub.NewRedisStreamsTransport(rdb, hub.DefaultStreamKey, hub.DefaultGroupName)
		h.SetCrossPodTransport(transport)
		log.Printf("cross-pod transport: redis streams (stream=%s group=%s)",
			hub.DefaultStreamKey, hub.DefaultGroupName)
	} else {
		log.Printf("cross-pod transport: noop (redis unavailable)")
	}

	registry := ws.NewRegistry()
	wsHandler := ws.NewHandler(registry, h, validator, allowAnonymous)

	// D-186: Yjs collaborative IDE WebSocket handler for /ws/ide/{sessionId}.
	// Reuses the same JWT validator and allowAnonymous flag as the /ws handler.
	yjsHandler := yjs.NewHandler(yjs.NewRoomRegistry(), validator, allowAnonymous)

	// D-149: start the Redis Pub/Sub subscribe loop for cross-pod broadcast.
	subscribeCtx, subscribeCancel := context.WithCancel(context.Background())
	defer subscribeCancel()
	h.StartSubscribe(subscribeCtx)

	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("ok"))
	})

	// D-149: WebSocket endpoint for real-time communication (board game live play,
	// notifications, etc.). JWT is validated via query param ?token=... or
	// Authorization: Bearer ... header.
	mux.Handle("/ws", wsHandler)

	// D-186: Yjs collaborative IDE WebSocket endpoint. Clients connect to
	// /ws/ide/{sessionId}?token=<JWT> and exchange binary y-websocket sync
	// frames for real-time collaborative editing.
	mux.Handle("/ws/ide/", yjsHandler)

	// D-118: wrap the entire mux with OTel HTTP middleware so every inbound
	// request becomes a server span and trace context is propagated to
	// downstream calls via the W3C traceparent header.
	handler := middleware.OTelHTTPMiddleware(mux)

	server := &http.Server{
		Addr:              ":" + port,
		Handler:           handler,
		ReadHeaderTimeout: 10 * time.Second,
	}

	// Graceful shutdown: flush pending OTel spans before exit.
	go func() {
		log.Printf("ws-gateway listening on :%s (ws=/ws, ide=/ws/ide/, auth=%v)", port, !allowAnonymous)
		if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("server error: %v", err)
		}
	}()

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, syscall.SIGINT, syscall.SIGTERM)
	<-stop

	shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := server.Shutdown(shutdownCtx); err != nil {
		log.Printf("http server shutdown error: %v", err)
	}
	// TracerProvider flush happens via the deferred ShutdownTracer above.
}
