package com.ep.data.cdc;

import java.io.Serializable;
import java.util.Objects;

/**
 * Generic CDC envelope carrying a single row change event from TiCDC → Pulsar.
 *
 * <p>D-028/C-07: Envelopes are serialized as Avro on the wire (no JSON CDC).
 * The {@code before}/{@code after} payloads are kept as JSON strings so a
 * single envelope type can carry any table's row image without per-table
 * generics.
 */
public class CdcEnvelope implements Serializable {

    private static final long serialVersionUID = 1L;

    private OpType opType;
    private long tsMs;
    private String sourceCell;
    private String table;
    private String before;
    private String after;

    public CdcEnvelope() {
        // no-arg for serialization frameworks
    }

    public CdcEnvelope(OpType opType, long tsMs, String sourceCell, String table,
                       String before, String after) {
        this.opType = opType;
        this.tsMs = tsMs;
        this.sourceCell = sourceCell;
        this.table = table;
        this.before = before;
        this.after = after;
    }

    /**
     * Build a CREATE event. {@code before} is null, {@code after} carries the new row.
     */
    public static CdcEnvelope createEvent(String table, String sourceCell, String after) {
        return new CdcEnvelope(OpType.CREATE, System.currentTimeMillis(), sourceCell, table, null, after);
    }

    /**
     * Build an UPDATE event carrying both the old and new row images.
     */
    public static CdcEnvelope updateEvent(String table, String sourceCell, String before, String after) {
        return new CdcEnvelope(OpType.UPDATE, System.currentTimeMillis(), sourceCell, table, before, after);
    }

    /**
     * Build a DELETE event. {@code after} is null, {@code before} carries the deleted row.
     */
    public static CdcEnvelope deleteEvent(String table, String sourceCell, String before) {
        return new CdcEnvelope(OpType.DELETE, System.currentTimeMillis(), sourceCell, table, before, null);
    }

    public OpType getOpType() { return opType; }
    public void setOpType(OpType opType) { this.opType = opType; }

    public long getTsMs() { return tsMs; }
    public void setTsMs(long tsMs) { this.tsMs = tsMs; }

    public String getSourceCell() { return sourceCell; }
    public void setSourceCell(String sourceCell) { this.sourceCell = sourceCell; }

    public String getTable() { return table; }
    public void setTable(String table) { this.table = table; }

    public String getBefore() { return before; }
    public void setBefore(String before) { this.before = before; }

    public String getAfter() { return after; }
    public void setAfter(String after) { this.after = after; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CdcEnvelope that)) return false;
        return tsMs == that.tsMs
                && opType == that.opType
                && Objects.equals(sourceCell, that.sourceCell)
                && Objects.equals(table, that.table)
                && Objects.equals(before, that.before)
                && Objects.equals(after, that.after);
    }

    @Override
    public int hashCode() {
        return Objects.hash(opType, tsMs, sourceCell, table, before, after);
    }

    @Override
    public String toString() {
        return "CdcEnvelope{opType=" + opType
                + ", tsMs=" + tsMs
                + ", sourceCell='" + sourceCell + '\''
                + ", table='" + table + '\''
                + ", before=" + before
                + ", after=" + after + '}';
    }
}
