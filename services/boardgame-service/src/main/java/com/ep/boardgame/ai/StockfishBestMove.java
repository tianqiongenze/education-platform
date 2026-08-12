package com.ep.boardgame.ai;

/**
 * D-193: Result of a Stockfish best-move query.
 *
 * @param bestMove   UCI-format best move e.g. "e2e4"; null when unavailable
 * @param ponderMove expected opponent reply, or null
 */
public record StockfishBestMove(String bestMove, String ponderMove) {}
