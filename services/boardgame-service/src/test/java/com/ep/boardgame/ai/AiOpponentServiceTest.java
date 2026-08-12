package com.ep.boardgame.ai;

import com.ep.boardgame.rule.XiangqiRuleEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * D-193: AiOpponentService unit tests — ELO→depth mapping + fallback.
 */
class AiOpponentServiceTest {

    private ObjectProvider<StockfishClient> sidecarProvider;
    private XiangqiRuleEngine ruleEngine;

    @BeforeEach
    void setUp() {
        sidecarProvider = mock(ObjectProvider.class);
        ruleEngine = new XiangqiRuleEngine();
    }

    @Test
    void mapRatingToDepthTime_allBands() {
        assertArrayEquals(new int[]{3, 500}, AiOpponentService.mapRatingToDepthTime(800));
        assertArrayEquals(new int[]{3, 500}, AiOpponentService.mapRatingToDepthTime(1199));
        assertArrayEquals(new int[]{6, 1000}, AiOpponentService.mapRatingToDepthTime(1200));
        assertArrayEquals(new int[]{6, 1000}, AiOpponentService.mapRatingToDepthTime(1599));
        assertArrayEquals(new int[]{10, 2000}, AiOpponentService.mapRatingToDepthTime(1600));
        assertArrayEquals(new int[]{10, 2000}, AiOpponentService.mapRatingToDepthTime(2000));
        assertArrayEquals(new int[]{15, 5000}, AiOpponentService.mapRatingToDepthTime(2001));
        assertArrayEquals(new int[]{15, 5000}, AiOpponentService.mapRatingToDepthTime(2500));
    }

    @Test
    void getAiMove_sidecarAvailable_returnsStockfishMove() {
        StockfishClient sidecar = mock(StockfishClient.class);
        when(sidecarProvider.getIfAvailable()).thenReturn(sidecar);
        when(sidecar.getBestMove(any(), anyInt(), anyInt()))
                .thenReturn(new StockfishBestMove("c2e2", null));

        AiOpponentService service = new AiOpponentService(sidecarProvider, ruleEngine);
        String move = service.getAiMove(XiangqiRuleEngine.INITIAL_FEN, 1500);
        assertEquals("c2e2", move);
    }

    @Test
    void getAiMove_sidecarUnavailable_fallsBackToRandomLegalMove() {
        when(sidecarProvider.getIfAvailable()).thenReturn(null);
        AiOpponentService service = new AiOpponentService(sidecarProvider, ruleEngine);

        String move = service.getAiMove(XiangqiRuleEngine.INITIAL_FEN, 1500);
        assertNotNull(move, "fallback must produce a legal move");
        assertEquals(4, move.length(), "UCI move is 4 chars");
    }

    @Test
    void getAiMove_sidecarReturnsNull_fallsBackToRandomLegalMove() {
        StockfishClient sidecar = mock(StockfishClient.class);
        when(sidecarProvider.getIfAvailable()).thenReturn(sidecar);
        when(sidecar.getBestMove(any(), anyInt(), anyInt())).thenReturn(null);

        AiOpponentService service = new AiOpponentService(sidecarProvider, ruleEngine);
        String move = service.getAiMove(XiangqiRuleEngine.INITIAL_FEN, 1500);
        assertNotNull(move);
        assertEquals(4, move.length());
    }

    @Test
    void getAiMove_usesDepthForRatingBand() {
        StockfishClient sidecar = mock(StockfishClient.class);
        when(sidecarProvider.getIfAvailable()).thenReturn(sidecar);
        when(sidecar.getBestMove(any(), eq(3), eq(500)))
                .thenReturn(new StockfishBestMove("depth3move", null));
        when(sidecar.getBestMove(any(), eq(15), eq(5000)))
                .thenReturn(new StockfishBestMove("depth15move", null));

        AiOpponentService service = new AiOpponentService(sidecarProvider, ruleEngine);
        assertEquals("depth3move", service.getAiMove(XiangqiRuleEngine.INITIAL_FEN, 900));
        assertEquals("depth15move", service.getAiMove(XiangqiRuleEngine.INITIAL_FEN, 2200));
    }

    private static int eq(int v) { return org.mockito.ArgumentMatchers.eq(v); }
}
