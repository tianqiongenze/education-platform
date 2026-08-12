package com.ep.data.pipeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PipelineValidatorTest {

    private static CdcPipelineConfig validBase() {
        return new CdcPipelineConfig(
                "order", "persistent://public/cdc/order_group", 32,
                300_000L, "ods", "ods_order", "checkpoint", 256, "zstd");
    }

    @Test
    void validConfig_passes() {
        assertDoesNotThrow(() -> PipelineValidator.validate(validBase()));
    }

    @Test
    void transactionalCommitMode_passesWithWarning() {
        CdcPipelineConfig c = validBase();
        c.setCommitMode("transactional");
        // C-03: warn but do not throw
        assertDoesNotThrow(() -> PipelineValidator.validate(c));
    }

    @Test
    void invalidParallelism_zeroThrows() {
        CdcPipelineConfig c = validBase();
        c.setParallelism(0);
        assertThrows(IllegalArgumentException.class, () -> PipelineValidator.validate(c));
    }

    @Test
    void invalidParallelism_tooLargeThrows() {
        CdcPipelineConfig c = validBase();
        c.setParallelism(257);
        assertThrows(IllegalArgumentException.class, () -> PipelineValidator.validate(c));
    }

    @Test
    void invalidCheckpointInterval_tooSmallThrows() {
        CdcPipelineConfig c = validBase();
        c.setCheckpointIntervalMs(500L);
        assertThrows(IllegalArgumentException.class, () -> PipelineValidator.validate(c));
    }

    @Test
    void invalidCheckpointInterval_tooLargeThrows() {
        CdcPipelineConfig c = validBase();
        c.setCheckpointIntervalMs(4_000_000L);
        assertThrows(IllegalArgumentException.class, () -> PipelineValidator.validate(c));
    }

    @Test
    void invalidCommitMode_throws() {
        CdcPipelineConfig c = validBase();
        c.setCommitMode("weird");
        assertThrows(IllegalArgumentException.class, () -> PipelineValidator.validate(c));
    }

    @Test
    void invalidTopicPattern_throws() {
        CdcPipelineConfig c = validBase();
        c.setPulsarTopic("kafka://topic");
        assertThrows(IllegalArgumentException.class, () -> PipelineValidator.validate(c));
    }

    @Test
    void blankTable_throws() {
        CdcPipelineConfig c = validBase();
        c.setSourceTable("  ");
        assertThrows(IllegalArgumentException.class, () -> PipelineValidator.validate(c));
    }

    @Test
    void blankIcebergTable_throws() {
        CdcPipelineConfig c = validBase();
        c.setIcebergTable("");
        assertThrows(IllegalArgumentException.class, () -> PipelineValidator.validate(c));
    }

    @Test
    void invalidTargetFileSize_throws() {
        CdcPipelineConfig c = validBase();
        c.setTargetFileSizeMb(0);
        assertThrows(IllegalArgumentException.class, () -> PipelineValidator.validate(c));
    }

    @Test
    void nullConfig_throws() {
        assertThrows(IllegalArgumentException.class, () -> PipelineValidator.validate(null));
    }
}
