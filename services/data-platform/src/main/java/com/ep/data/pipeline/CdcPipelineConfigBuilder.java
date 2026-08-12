package com.ep.data.pipeline;

/**
 * Fluent builder for {@link CdcPipelineConfig}.
 */
public final class CdcPipelineConfigBuilder {

    private String sourceTable;
    private String pulsarTopic;
    private int parallelism;
    private long checkpointIntervalMs = CdcPipelineConfig.DEFAULT_CHECKPOINT_INTERVAL_MS;
    private String icebergDatabase;
    private String icebergTable;
    private String commitMode = CdcPipelineConfig.DEFAULT_COMMIT_MODE;
    private int targetFileSizeMb = CdcPipelineConfig.DEFAULT_TARGET_FILE_SIZE_MB;
    private String compressionCodec = CdcPipelineConfig.DEFAULT_COMPRESSION_CODEC;

    public CdcPipelineConfigBuilder() {
    }

    public CdcPipelineConfigBuilder sourceTable(String sourceTable) {
        this.sourceTable = sourceTable;
        return this;
    }

    public CdcPipelineConfigBuilder pulsarTopic(String pulsarTopic) {
        this.pulsarTopic = pulsarTopic;
        return this;
    }

    public CdcPipelineConfigBuilder parallelism(int parallelism) {
        this.parallelism = parallelism;
        return this;
    }

    public CdcPipelineConfigBuilder checkpointIntervalMs(long checkpointIntervalMs) {
        this.checkpointIntervalMs = checkpointIntervalMs;
        return this;
    }

    public CdcPipelineConfigBuilder icebergDatabase(String icebergDatabase) {
        this.icebergDatabase = icebergDatabase;
        return this;
    }

    public CdcPipelineConfigBuilder icebergTable(String icebergTable) {
        this.icebergTable = icebergTable;
        return this;
    }

    public CdcPipelineConfigBuilder commitMode(String commitMode) {
        this.commitMode = commitMode;
        return this;
    }

    public CdcPipelineConfigBuilder targetFileSizeMb(int targetFileSizeMb) {
        this.targetFileSizeMb = targetFileSizeMb;
        return this;
    }

    public CdcPipelineConfigBuilder compressionCodec(String compressionCodec) {
        this.compressionCodec = compressionCodec;
        return this;
    }

    public CdcPipelineConfig build() {
        return new CdcPipelineConfig(sourceTable, pulsarTopic, parallelism, checkpointIntervalMs,
                icebergDatabase, icebergTable, commitMode, targetFileSizeMb, compressionCodec);
    }
}
