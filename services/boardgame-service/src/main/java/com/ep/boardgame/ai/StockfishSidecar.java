package com.ep.boardgame.ai;

import com.ep.boardgame.ai.proto.BestMoveRequest;
import com.ep.boardgame.ai.proto.BestMoveResponse;
import com.ep.boardgame.ai.proto.EvalRequest;
import com.ep.boardgame.ai.proto.EvalResponse;
import com.ep.boardgame.ai.proto.StockfishServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * D-193: Stockfish gRPC sidecar client.
 * <p>
 * Connects to the Stockfish sidecar running in the same pod
 * ({@code localhost:50051} by default, override via {@code STOCKFISH_GRPC_ADDR}).
 * The bean is only created when {@code ep.stockfish.enabled=true}; otherwise
 * {@link AiOpponentService} falls back to a random legal move from the
 * {@link com.ep.boardgame.rule.XiangqiRuleEngine}.
 * <p>
 * EP_DEV_ONLY: all gRPC errors are caught and logged; methods return {@code null}
 * so callers can degrade gracefully (AI opponent disabled) without crashing.
 * <p>
 * AOT note: gRPC channels are not build-time initialisable, so we keep channel
 * creation in {@link PostConstruct} and close it in {@link PreDestroy}.
 */
@Component
@ConditionalOnProperty(name = "ep.stockfish.enabled", havingValue = "true")
public class StockfishSidecar implements StockfishClient {

    private static final Logger log = LoggerFactory.getLogger(StockfishSidecar.class);

    @Value("${ep.stockfish.grpc-address:localhost:50051}")
    private String grpcAddress;

    @Value("${ep.stockfish.timeout-ms:5000}")
    private long timeoutMs;

    private ManagedChannel channel;
    private StockfishServiceGrpc.StockfishServiceBlockingStub stub;

    @PostConstruct
    public void init() {
        try {
            log.info("D-193: opening Stockfish gRPC channel to {}", grpcAddress);
            channel = ManagedChannelBuilder.forTarget(grpcAddress)
                    .usePlaintext()
                    .build();
            stub = StockfishServiceGrpc.newBlockingStub(channel);
        } catch (Exception e) {
            log.warn("D-193: failed to open Stockfish gRPC channel (EP_DEV_ONLY): {}", e.getMessage());
            channel = null;
            stub = null;
        }
    }

    @PreDestroy
    public void shutdown() {
        if (channel != null) {
            try {
                channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public ManagedChannel channel() {
        return channel;
    }

    @Override
    public StockfishBestMove getBestMove(String fen, int depth, int timeLimitMs) {
        if (stub == null) {
            log.debug("D-193: Stockfish stub unavailable, returning null best move");
            return null;
        }
        try {
            BestMoveResponse resp = stub.withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS)
                    .getBestMove(BestMoveRequest.newBuilder()
                            .setFen(fen)
                            .setDepth(depth)
                            .setTimeLimitMs(timeLimitMs)
                            .build());
            String best = resp.getBestMove().isBlank() ? null : resp.getBestMove();
            String ponder = resp.getPonderMove().isBlank() ? null : resp.getPonderMove();
            return new StockfishBestMove(best, ponder);
        } catch (Exception e) {
            log.warn("D-193: Stockfish getBestMove failed (EP_DEV_ONLY): {}", e.getMessage());
            return null;
        }
    }

    @Override
    public StockfishEvaluation getEvaluation(String fen, int depth) {
        if (stub == null) {
            log.debug("D-193: Stockfish stub unavailable, returning null evaluation");
            return null;
        }
        try {
            EvalResponse resp = stub.withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS)
                    .getEvaluation(EvalRequest.newBuilder()
                            .setFen(fen)
                            .setDepth(depth)
                            .build());
            return new StockfishEvaluation(
                    resp.getScoreCentipawns(),
                    resp.getMateIn(),
                    resp.getBestLine());
        } catch (Exception e) {
            log.warn("D-193: Stockfish getEvaluation failed (EP_DEV_ONLY): {}", e.getMessage());
            return null;
        }
    }
}
