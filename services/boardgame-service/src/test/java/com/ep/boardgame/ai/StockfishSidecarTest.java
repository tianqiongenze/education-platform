package com.ep.boardgame.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * D-193: StockfishSidecar unit tests — graceful degradation when gRPC fails.
 * <p>
 * The sidecar bean is only registered when {@code ep.stockfish.enabled=true},
 * but the class itself can be instantiated directly to verify the degradation
 * path without a real gRPC server.
 */
class StockfishSidecarTest {

    private StockfishSidecar sidecar;

    @BeforeEach
    void setUp() {
        sidecar = new StockfishSidecar();
        // Without calling init(), channel/stub remain null — exercising the
        // EP_DEV_ONLY degradation path.
    }

    @Test
    void getBestMove_stubNull_returnsNull() {
        assertNull(sidecar.getBestMove("rnbakabnr/9/9/9/9/9/9/9/9/RNBAKABNR w - - 0 1", 5, 1000));
    }

    @Test
    void getEvaluation_stubNull_returnsNull() {
        assertNull(sidecar.getEvaluation("rnbakabnr/9/9/9/9/9/9/9/9/RNBAKABNR w - - 0 1", 5));
    }

    @Test
    void channel_isNullBeforeInit() {
        assertNull(sidecar.channel());
    }

    @Test
    void shutdown_isSafeWhenChannelNull() {
        assertDoesNotThrow(() -> sidecar.shutdown());
    }

    @Test
    void stockfishClient_interface_canBeMocked() {
        StockfishClient mock = mock(StockfishClient.class);
        when(mock.getBestMove(any(), anyInt(), anyInt()))
                .thenReturn(new StockfishBestMove("e2e4", "e7e5"));
        StockfishBestMove result = mock.getBestMove("fen", 5, 1000);
        assertEquals("e2e4", result.bestMove());
        assertEquals("e7e5", result.ponderMove());
        verify(mock).getBestMove("fen", 5, 1000);
    }
}
