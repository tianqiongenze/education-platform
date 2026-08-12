package com.ep.data.pipeline;

import java.util.Objects;

/**
 * Configuration for one CDC pipeline: TiDB table → Pulsar topic → Flink CDC → Iceberg table.
 *
 * <p>D-028: CDC bus = TiCDC → Pulsar (Avro) → Flink CDC 3.x YAML → Iceberg.
 * C-03: Flink→Iceberg MUST use checkpoint commit; default commitMode="checkpoint".
 */
public class CdcPipelineConfig {

    /** Default checkpoint interval (5 min) per C-03. */
    public static final long DEFAULT_CHECKPOINT_INTERVAL_MS = 300_000L;
    /** C-03: recommended commit mode. */
    public static final String DEFAULT_COMMIT_MODE = "checkpoint";
    public static final int DEFAULT_TARGET_FILE_SIZE_MB = 256;
    public static final String DEFAULT_COMPRESSION_CODEC = "zstd";

    private String sourceTable;
    private String pulsarTopic;
    private int parallelism;
    private long checkpointIntervalMs = DEFAULT_CHECKPOINT_INTERVAL_MS;
    private String icebergDatabase;
    private String icebergTable;
    private String commitMode = DEFAULT_COMMIT_MODE;
    private int targetFileSizeMb = DEFAULT_TARGET_FILE_SIZE_MB;
    private String compressionCodec = DEFAULT_COMPRESSION_CODEC;

    public CdcPipelineConfig() {
    }

    public CdcPipelineConfig(String sourceTable, String pulsarTopic, int parallelism,
                             long checkpointIntervalMs, String icebergDatabase,
                             String icebergTable, String commitMode,
                             int targetFileSizeMb, String compressionCodec) {
        this.sourceTable = sourceTable;
        this.pulsarTopic = pulsarTopic;
        this.parallelism = parallelism;
        this.checkpointIntervalMs = checkpointIntervalMs;
        this.icebergDatabase = icebergDatabase;
        this.icebergTable = icebergTable;
        this.commitMode = commitMode;
        this.targetFileSizeMb = targetFileSizeMb;
        this.compressionCodec = compressionCodec;
    }

    /**
     * Order pipeline: order_0000..order_1023 → ods.ods_order, parallelism 32.
     */
    public static CdcPipelineConfig orderPipeline() {
        return new CdcPipelineConfig(
                "order",
                "persistent://public/cdc/order_group",
                32,
                DEFAULT_CHECKPOINT_INTERVAL_MS,
                "ods",
                "ods_order",
                DEFAULT_COMMIT_MODE,
                DEFAULT_TARGET_FILE_SIZE_MB,
                DEFAULT_COMPRESSION_CODEC);
    }

    /**
     * Learning-event pipeline: parallelism 64 (high-volume stream).
     */
    public static CdcPipelineConfig learningEventPipeline() {
        return new CdcPipelineConfig(
                "learning_event",
                "persistent://public/cdc/learning_event_group",
                64,
                DEFAULT_CHECKPOINT_INTERVAL_MS,
                "ods",
                "ods_learning_event",
                DEFAULT_COMMIT_MODE,
                DEFAULT_TARGET_FILE_SIZE_MB,
                DEFAULT_COMPRESSION_CODEC);
    }

    /**
     * User pipeline: parallelism 8 (low-volume dimension table).
     */
    public static CdcPipelineConfig userPipeline() {
        return new CdcPipelineConfig(
                "user",
                "persistent://public/cdc/user_group",
                8,
                DEFAULT_CHECKPOINT_INTERVAL_MS,
                "ods",
                "ods_user",
                DEFAULT_COMMIT_MODE,
                DEFAULT_TARGET_FILE_SIZE_MB,
                DEFAULT_COMPRESSION_CODEC);
    }

    public String getSourceTable() { return sourceTable; }
    public void setSourceTable(String sourceTable) { this.sourceTable = sourceTable; }

    public String getPulsarTopic() { return pulsarTopic; }
    public void setPulsarTopic(String pulsarTopic) { this.pulsarTopic = pulsarTopic; }

    public int getParallelism() { return parallelism; }
    public void setParallelism(int parallelism) { this.parallelism = parallelism; }

    public long getCheckpointIntervalMs() { return checkpointIntervalMs; }
    public void setCheckpointIntervalMs(long checkpointIntervalMs) { this.checkpointIntervalMs = checkpointIntervalMs; }

    public String getIcebergDatabase() { return icebergDatabase; }
    public void setIcebergDatabase(String icebergDatabase) { this.icebergDatabase = icebergDatabase; }

    public String getIcebergTable() { return icebergTable; }
    public void setIcebergTable(String icebergTable) { this.icebergTable = icebergTable; }

    public String getCommitMode() { return commitMode; }
    public void setCommitMode(String commitMode) { this.commitMode = commitMode; }

    public int getTargetFileSizeMb() { return targetFileSizeMb; }
    public void setTargetFileSizeMb(int targetFileSizeMb) { this.targetFileSizeMb = targetFileSizeMb; }

    public String getCompressionCodec() { return compressionCodec; }
    public void setCompressionCodec(String compressionCodec) { this.compressionCodec = compressionCodec; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CdcPipelineConfig that)) return false;
        return parallelism == that.parallelism
                && checkpointIntervalMs == that.checkpointIntervalMs
                && targetFileSizeMb == that.targetFileSizeMb
                && Objects.equals(sourceTable, that.sourceTable)
                && Objects.equals(pulsarTopic, that.pulsarTopic)
                && Objects.equals(icebergDatabase, that.icebergDatabase)
                && Objects.equals(icebergTable, that.icebergTable)
                && Objects.equals(commitMode, that.commitMode)
                && Objects.equals(compressionCodec, that.compressionCodec);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sourceTable, pulsarTopic, parallelism, checkpointIntervalMs,
                icebergDatabase, icebergTable, commitMode, targetFileSizeMb, compressionCodec);
    }

    @Override
    public String toString() {
        return "CdcPipelineConfig{sourceTable='" + sourceTable + "', pulsarTopic='" + pulsarTopic
                + "', parallelism=" + parallelism + ", checkpointIntervalMs=" + checkpointIntervalMs
                + ", icebergDatabase='" + icebergDatabase + "', icebergTable='" + icebergTable
                + "', commitMode='" + commitMode + "', targetFileSizeMb=" + targetFileSizeMb
                + ", compressionCodec='" + compressionCodec + '\'' + '}';
    }
}
