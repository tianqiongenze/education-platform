package com.ep.data.pipeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class CdcPipelineConfigTest {

    @Test
    void orderPipeline_returnsCorrectDefaults() {
        CdcPipelineConfig c = CdcPipelineConfig.orderPipeline();
        assertNotNull(c);
        assertEquals("order", c.getSourceTable());
        assertEquals("persistent://public/cdc/order_group", c.getPulsarTopic());
        assertEquals(32, c.getParallelism());
        assertEquals("ods", c.getIcebergDatabase());
        assertEquals("ods_order", c.getIcebergTable());
        assertEquals("checkpoint", c.getCommitMode());
        assertEquals(256, c.getTargetFileSizeMb());
        assertEquals("zstd", c.getCompressionCodec());
        assertEquals(CdcPipelineConfig.DEFAULT_CHECKPOINT_INTERVAL_MS, c.getCheckpointIntervalMs());
    }

    @Test
    void learningEventPipeline_returnsCorrectDefaults() {
        CdcPipelineConfig c = CdcPipelineConfig.learningEventPipeline();
        assertEquals("learning_event", c.getSourceTable());
        assertEquals("persistent://public/cdc/learning_event_group", c.getPulsarTopic());
        assertEquals(64, c.getParallelism());
        assertEquals("ods_learning_event", c.getIcebergTable());
    }

    @Test
    void userPipeline_returnsCorrectDefaults() {
        CdcPipelineConfig c = CdcPipelineConfig.userPipeline();
        assertEquals("user", c.getSourceTable());
        assertEquals("persistent://public/cdc/user_group", c.getPulsarTopic());
        assertEquals(8, c.getParallelism());
        assertEquals("ods_user", c.getIcebergTable());
    }

    @Test
    void builder_buildsConfig() {
        CdcPipelineConfig c = new CdcPipelineConfigBuilder()
                .sourceTable("order")
                .pulsarTopic("persistent://public/cdc/order_group")
                .parallelism(16)
                .checkpointIntervalMs(60_000L)
                .icebergDatabase("ods")
                .icebergTable("ods_order")
                .build();
        assertEquals("order", c.getSourceTable());
        assertEquals(16, c.getParallelism());
        assertEquals(60_000L, c.getCheckpointIntervalMs());
        assertEquals("ods_order", c.getIcebergTable());
        // defaults applied
        assertEquals("checkpoint", c.getCommitMode());
        assertEquals("zstd", c.getCompressionCodec());
    }
}
