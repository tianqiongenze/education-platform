// Command live-go starts the SRS + LiveKit live streaming service (D-187).
//
// The server listens on :8090 and exposes:
//   GET  /healthz                         health check
//   POST /api/v1/live/rooms               REST: create live room
//   GET  /api/v1/live/rooms/{roomId}/ingest  WHIP ingest URL
//   GET  /api/v1/live/rooms/{roomId}/play    WHEP/HLS play URL
//   POST /api/v1/live/rooms/{roomId}/interact LiveKit token for interactive mode
//   /ws/live/{roomId}                     WebSocket: danmaku/chat
//   /whip/{roomId}                        WHIP push proxy → SRS
//   /whep/{roomId}                        WHEP pull proxy → SRS
//
// The service degrades gracefully: if LIVEKIT_URL is unset, interactive mode
// is disabled (returns 503 on /interact) but broadcast (SRS) still works.
// If SRS_ORIGINS is unset, WHIP/WHEP proxying returns 503.
package main

import (
	"context"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/redis/go-redis/v9"

	"github.com/education-platform/live-go/api"
	"github.com/education-platform/live-go/danmaku"
	"github.com/education-platform/live-go/livekit"
	"github.com/education-platform/live-go/srs"
)

func main() {
	addr := os.Getenv("LIVE_GO_ADDR")
	if addr == "" {
		addr = ":8090"
	}
	redisAddr := os.Getenv("REDIS_ADDR")
	if redisAddr == "" {
		redisAddr = "redis-cluster:6379"
	}

	rdb := redis.NewClient(&redis.Options{Addr: redisAddr})

	// SRS origin router (optional — broadcast disabled if unset).
	var router *srs.OriginRouter
	router, err := srs.NewOriginRouter(rdb)
	if err != nil {
		log.Printf("live-go: SRS origin router disabled: %v (WHIP/WHEP will return 503)", err)
	}

	// LiveKit SFU manager (optional — interactive disabled if unset).
	lkClient, lkErr := livekit.NewDefaultClient()
	if lkErr != nil {
		log.Printf("live-go: LiveKit disabled: %v (interactive mode returns 503)", lkErr)
	}
	sfu := livekit.NewSfuManager(lkClient)

	// Danmaku pipeline.
	pipeline := danmaku.NewDanmakuPipeline(rdb)

	mux := http.NewServeMux()

	// REST + health.
	rest := api.NewHandler(sfu)
	rest.Register(mux)

	// WebSocket for danmaku.
	wsHandler := api.NewWSHandler(pipeline)
	wsHandler.Register(mux)

	// WHIP/WHEP proxy (only if router is configured).
	if router != nil {
		proxy := srs.NewProxy(router, srs.DefaultProxyConfig())
		mux.HandleFunc("POST /whip/{roomId}", proxy.HandleWHIP)
		mux.HandleFunc("POST /whep/{roomId}", proxy.HandleWHEP)
	} else {
		mux.HandleFunc("POST /whip/{roomId}", func(w http.ResponseWriter, r *http.Request) {
			http.Error(w, "SRS not configured (SRS_ORIGINS empty)", http.StatusServiceUnavailable)
		})
		mux.HandleFunc("POST /whep/{roomId}", func(w http.ResponseWriter, r *http.Request) {
			http.Error(w, "SRS not configured (SRS_ORIGINS empty)", http.StatusServiceUnavailable)
		})
	}

	srv := &http.Server{
		Addr:              addr,
		Handler:           mux,
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       60 * time.Second,
	}

	// Graceful shutdown on SIGINT/SIGTERM.
	go func() {
		log.Printf("live-go: listening on %s", addr)
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("live-go: server error: %v", err)
		}
	}()

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, syscall.SIGINT, syscall.SIGTERM)
	<-stop
	log.Printf("live-go: shutting down...")

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := srv.Shutdown(ctx); err != nil {
		log.Printf("live-go: shutdown error: %v", err)
	}
	_ = rdb.Close()
	log.Printf("live-go: stopped")
}
