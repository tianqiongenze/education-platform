package com.ep.boardgame.ai;

import com.ep.boardgame.common.PlayerSide;
import com.ep.boardgame.rule.Move;
import com.ep.boardgame.rule.XiangqiRuleEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * D-193: AI opponent service.
 * <p>
 * Maps a player's ELO rating to a Stockfish search depth/time limit, queries the
 * sidecar for the best move, and degrades gracefully to a random legal move
 * from {@link XiangqiRuleEngine} when the sidecar is unavailable (EP_DEV_ONLY).
 * <p>
 * Depth/time mapping:
 * <ul>
 *   <li>rating &lt; 1200 → depth 3, 500 ms</li>
 *   <li>1200–1599 → depth 6, 1000 ms</li>
 *   <li>1600–2000 → depth 10, 2000 ms</li>
 *   <li>&gt; 2000 → depth 15, 5000 ms</li>
 * </ul>
 */
@Service
public class AiOpponentService {

    private static final Logger log = LoggerFactory.getLogger(AiOpponentService.class);

    private final ObjectProvider<StockfishClient> sidecarProvider;
    private final XiangqiRuleEngine ruleEngine;

    public AiOpponentService(ObjectProvider<StockfishClient> sidecarProvider,
                             XiangqiRuleEngine ruleEngine) {
        this.sidecarProvider = sidecarProvider;
        this.ruleEngine = ruleEngine;
    }

    /**
     * Get the AI's best move for the given position, scaled to the player rating.
     *
     * @param fen           current board position in FEN notation
     * @param playerRating  the human opponent's ELO rating (controls AI strength)
     * @return UCI move string, or null if no legal move exists
     */
    public String getAiMove(String fen, int playerRating) {
        StockfishClient sidecar = sidecarProvider.getIfAvailable();
        if (sidecar != null) {
            int[] depthTime = mapRatingToDepthTime(playerRating);
            StockfishBestMove best = sidecar.getBestMove(fen, depthTime[0], depthTime[1]);
            if (best != null && best.bestMove() != null) {
                log.debug("D-193: Stockfish move={} (depth={}, rating={})",
                        best.bestMove(), depthTime[0], playerRating);
                return best.bestMove();
            }
            log.warn("D-193: Stockfish returned no move, falling back to random legal move");
        } else {
            log.debug("D-193: Stockfish sidecar unavailable, using random legal move");
        }
        return randomLegalMoveUci(fen);
    }

    /** Map an ELO rating to {depth, timeLimitMs}. */
    static int[] mapRatingToDepthTime(int rating) {
        if (rating < 1200) return new int[]{3, 500};
        if (rating < 1600) return new int[]{6, 1000};
        if (rating <= 2000) return new int[]{10, 2000};
        return new int[]{15, 5000};
    }

    /**
     * Pick a uniformly random legal move from the position and return it in UCI.
     *
     * @return UCI string, or null if there are no legal moves
     */
    private String randomLegalMoveUci(String fen) {
        // Determine side to move from FEN (field index 1, 'w'/'b'). For Xiangqi
        // FEN we reuse the engine's convention where lowercase=RED ('w') and
        // uppercase=BLACK ('b'). RED moves first.
        PlayerSide side = sideToMoveFromFen(fen);
        List<Move> legal = ruleEngine.listLegalMoves(fen);
        if (legal == null || legal.isEmpty()) {
            return null;
        }
        // listLegalMoves may return moves with placeholder side/moveNumber;
        // rebuild a Move with the correct side so toUci() still produces valid coords.
        Move picked = legal.get(ThreadLocalRandom.current().nextInt(legal.size()));
        Move normalized = new Move(picked.fromRow(), picked.fromCol(),
                picked.toRow(), picked.toCol(), side, picked.moveNumber());
        return normalized.toUci();
    }

    private static PlayerSide sideToMoveFromFen(String fen) {
        if (fen == null) return PlayerSide.RED;
        String[] parts = fen.split(" ");
        if (parts.length < 2) return PlayerSide.RED;
        return "b".equalsIgnoreCase(parts[1]) ? PlayerSide.BLACK : PlayerSide.RED;
    }
}
