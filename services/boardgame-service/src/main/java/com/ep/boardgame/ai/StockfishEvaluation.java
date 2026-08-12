package com.ep.boardgame.ai;

/**
 * D-193: Result of a Stockfish position evaluation.
 *
 * @param scoreCentipawns evaluation from the side-to-move perspective (0 when mate_in set)
 * @param mateIn          forced mate in N moves, 0 if no mate
 * @param bestLine        principal variation (PV), space-separated UCI moves
 */
public record StockfishEvaluation(int scoreCentipawns, int mateIn, String bestLine) {}
