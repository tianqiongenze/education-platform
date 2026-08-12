package com.ep.data.ingest;

import com.ep.data.cdc.CdcEnvelope;

/**
 * Disruptor event carrier for CDC ingest pipeline (SDD §5.5).
 *
 * <p>Wraps a {@link CdcEnvelope} as a Disruptor slot. The Disruptor pre-allocates
 * these objects and reuses them via the translator pattern — no per-event allocation.
 */
public final class IngestEvent {
    private CdcEnvelope envelope;
    private long ingestTs;
    private String sourceCell;

    public CdcEnvelope getEnvelope() { return envelope; }
    public void setEnvelope(CdcEnvelope envelope) { this.envelope = envelope; }
    public long getIngestTs() { return ingestTs; }
    public void setIngestTs(long ingestTs) { this.ingestTs = ingestTs; }
    public String getSourceCell() { return sourceCell; }
    public void setSourceCell(String sourceCell) { this.sourceCell = sourceCell; }

    @Override
    public String toString() {
        return "IngestEvent{envelope=" + envelope + ", ingestTs=" + ingestTs
                + ", sourceCell='" + sourceCell + '\'' + '}';
    }
}
