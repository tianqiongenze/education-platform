package com.ep.disruptor;

/**
 * D-171: Reactivated for data-platform ingest pipeline (SDD §5.5 Worker
 * Separation Pattern). Outcome of a Disruptor publish attempt:
 * PUBLISHED (slot reserved) or DROPPED (RingBuffer full, degraded).
 */
public enum PublishResult { PUBLISHED, DROPPED }
