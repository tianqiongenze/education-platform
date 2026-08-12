package com.ep.boardgame.room;

import com.ep.boardgame.common.DomainEvent;
import com.ep.boardgame.common.PlayerSide;
import com.ep.boardgame.rule.Move;
import com.ep.boardgame.rule.XiangqiRuleEngine;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D-149: GameMatch aggregate root state machine tests.
 */
class GameMatchTest {

    private GameMatch newOngoingMatch() {
        GameMatch match = new GameMatch();
        match.setMatchId(1L);
        match.setRoomId(1L);
        match.setTenantId(10L);
        match.setRedPlayerId(100L);
        match.setBlackPlayerId(200L);
        match.setFenState(XiangqiRuleEngine.INITIAL_FEN);
        match.setMoveHistory("[]");
        match.setResult(GameMatch.RESULT_ONGOING);
        match.setMoveCount(0);
        match.setCurrentSide(0);
        match.setCreatedAt(System.currentTimeMillis());
        return match;
    }

    @Test
    void applyMove_ongoingMatch_updatesFenAndSide() {
        GameMatch match = newOngoingMatch();
        XiangqiRuleEngine engine = new XiangqiRuleEngine();
        Move move = new Move(0, 1, 2, 2, PlayerSide.RED, 1); // Horse move (Red at top)
        String newFen = engine.applyMove(match.getFenState(), move);
        match.applyMove(newFen, move);
        assertEquals(1, match.getMoveCount());
        assertEquals(1, match.getCurrentSide()); // BLACK's turn
        assertNotEquals(XiangqiRuleEngine.INITIAL_FEN, match.getFenState());
    }

    @Test
    void applyMatch_finishedMatch_throws() {
        GameMatch match = newOngoingMatch();
        match.setResult(GameMatch.RESULT_RED_WIN);
        Move move = new Move(0, 1, 2, 2, PlayerSide.RED, 1);
        assertThrows(IllegalStateException.class, () -> match.applyMove("fen", move));
    }

    @Test
    void finish_ongoingMatch_success() {
        GameMatch match = newOngoingMatch();
        match.finish(GameMatch.RESULT_RED_WIN);
        assertEquals(GameMatch.RESULT_RED_WIN, match.getResult());
        assertNotNull(match.getFinishedAt());
        List<DomainEvent> events = match.pullEvents();
        assertFalse(events.isEmpty());
        assertTrue(events.stream().anyMatch(e -> e instanceof MatchFinishedEvent));
    }

    @Test
    void finish_alreadyFinished_throws() {
        GameMatch match = newOngoingMatch();
        match.finish(GameMatch.RESULT_DRAW);
        assertThrows(IllegalStateException.class, () -> match.finish(GameMatch.RESULT_RED_WIN));
    }

    @Test
    void finish_invalidResult_throws() {
        GameMatch match = newOngoingMatch();
        assertThrows(IllegalArgumentException.class, () -> match.finish(99));
    }

    @Test
    void resign_redResigns_blackWins() {
        GameMatch match = newOngoingMatch();
        match.resign(PlayerSide.RED);
        assertEquals(GameMatch.RESULT_BLACK_WIN, match.getResult());
        assertNotNull(match.getFinishedAt());
    }

    @Test
    void resign_blackResigns_redWins() {
        GameMatch match = newOngoingMatch();
        match.resign(PlayerSide.BLACK);
        assertEquals(GameMatch.RESULT_RED_WIN, match.getResult());
    }

    @Test
    void resign_finishedMatch_throws() {
        GameMatch match = newOngoingMatch();
        match.finish(GameMatch.RESULT_DRAW);
        assertThrows(IllegalStateException.class, () -> match.resign(PlayerSide.RED));
    }

    @Test
    void pullEvents_clearsEvents() {
        GameMatch match = newOngoingMatch();
        match.finish(GameMatch.RESULT_RED_WIN);
        assertFalse(match.pullEvents().isEmpty());
        assertTrue(match.pullEvents().isEmpty());
    }
}
