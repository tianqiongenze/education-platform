package com.ep.data.cdc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CdcEnvelopeTest {

    private static final String AFTER = "{\"order_id\":1}";
    private static final String BEFORE = "{\"order_id\":1,\"status\":0}";

    @Test
    void createEvent_setsOpTypeCreateAndNullBefore() {
        CdcEnvelope e = CdcEnvelope.createEvent("order", "cell-a", AFTER);
        assertEquals(OpType.CREATE, e.getOpType());
        assertEquals("order", e.getTable());
        assertEquals("cell-a", e.getSourceCell());
        assertEquals(AFTER, e.getAfter());
        assertNull(e.getBefore());
        assertTrue(e.getTsMs() > 0);
    }

    @Test
    void updateEvent_setsOpTypeUpdateAndBothImages() {
        CdcEnvelope e = CdcEnvelope.updateEvent("order", "cell-a", BEFORE, AFTER);
        assertEquals(OpType.UPDATE, e.getOpType());
        assertEquals(BEFORE, e.getBefore());
        assertEquals(AFTER, e.getAfter());
        assertEquals("order", e.getTable());
        assertEquals("cell-a", e.getSourceCell());
    }

    @Test
    void deleteEvent_setsOpTypeDeleteAndNullAfter() {
        CdcEnvelope e = CdcEnvelope.deleteEvent("order", "cell-a", BEFORE);
        assertEquals(OpType.DELETE, e.getOpType());
        assertEquals(BEFORE, e.getBefore());
        assertNull(e.getAfter());
        assertEquals("order", e.getTable());
    }

    @Test
    void createEvent_tsMsIsRecent() {
        long before = System.currentTimeMillis();
        CdcEnvelope e = CdcEnvelope.createEvent("t", "c", AFTER);
        long after = System.currentTimeMillis();
        assertTrue(e.getTsMs() >= before && e.getTsMs() <= after);
    }

    @Test
    void allArgsConstructor_setsAllFields() {
        CdcEnvelope e = new CdcEnvelope(OpType.READ, 123L, "cell", "tbl", BEFORE, AFTER);
        assertEquals(OpType.READ, e.getOpType());
        assertEquals(123L, e.getTsMs());
        assertEquals("cell", e.getSourceCell());
        assertEquals("tbl", e.getTable());
        assertEquals(BEFORE, e.getBefore());
        assertEquals(AFTER, e.getAfter());
    }

    @Test
    void settersMutateState() {
        CdcEnvelope e = new CdcEnvelope();
        e.setOpType(OpType.UPDATE);
        e.setTsMs(999L);
        e.setSourceCell("cell-x");
        e.setTable("tbl-x");
        e.setBefore("b");
        e.setAfter("a");
        assertEquals(OpType.UPDATE, e.getOpType());
        assertEquals(999L, e.getTsMs());
        assertEquals("cell-x", e.getSourceCell());
        assertEquals("tbl-x", e.getTable());
        assertEquals("b", e.getBefore());
        assertEquals("a", e.getAfter());
    }

    @Test
    void equalsAndHashCodeWork() {
        long ts = 100L;
        CdcEnvelope a = new CdcEnvelope(OpType.CREATE, ts, "c", "t", null, AFTER);
        CdcEnvelope b = new CdcEnvelope(OpType.CREATE, ts, "c", "t", null, AFTER);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotNull(a.toString());
    }
}
