package com.ep.boardgame.ai;

import io.grpc.ManagedChannel;

/**
 * D-193: Minimal abstraction over the Stockfish gRPC sidecar so that the
 * sidecar client can be mocked in tests without standing up a real gRPC
 * channel. {@link StockfishSidecar} is the production implementation.
 */
public interface StockfishClient {

    /** @return the underlying gRPC channel, or null when the sidecar is disabled. */
    ManagedChannel channel();

    /**
     * Ask Stockfish for the best move in the given position.
     *
     * @param fen          board position in FEN notation
     * @param depth        search depth (0 = unlimited)
     * @param timeLimitMs  wall-clock limit; 0 = unlimited
     * @return best move + ponder, or null when the sidecar is unavailable
     */
    StockfishBestMove getBestMove(String fen, int depth, int timeLimitMs);

    /**
     * Ask Stockfish for a static evaluation of the position.
     *
     * @param fen   board position in FEN notation
     * @param depth search depth (0 = unlimited)
     * @return evaluation, or null when the sidecar is unavailable
     */
    StockfishEvaluation getEvaluation(String fen, int depth);
}
