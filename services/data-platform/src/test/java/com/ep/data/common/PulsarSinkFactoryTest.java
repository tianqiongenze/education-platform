package com.ep.data.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PulsarSinkFactoryTest {

    @Test
    void createProducerConfig_returnsExpectedFields() {
        PulsarProducerConfig cfg = PulsarSinkFactory.createProducerConfig(
                "persistent://public/cdc/order_group", "AVRO");
        assertEquals("persistent://public/cdc/order_group", cfg.getTopic());
        assertEquals("AVRO", cfg.getSchemaType());
        assertEquals(PulsarProducerConfig.DEFAULT_SEND_TIMEOUT_MS, cfg.getSendTimeoutMs());
        assertEquals(PulsarProducerConfig.DEFAULT_BLOCK_IF_QUEUE_FULL, cfg.isBlockIfQueueFull());
        assertEquals(PulsarProducerConfig.DEFAULT_MAX_PENDING_MESSAGES, cfg.getMaxPendingMessages());
    }

    @Test
    void createProducerConfig_defaultsToAvroWhenBlank() {
        PulsarProducerConfig cfg = PulsarSinkFactory.createProducerConfig(
                "persistent://public/cdc/order_group", "");
        assertEquals("AVRO", cfg.getSchemaType());
    }

    @Test
    void createProducerConfig_throwsOnBlankTopic() {
        assertThrows(IllegalArgumentException.class,
                () -> PulsarSinkFactory.createProducerConfig(null, "AVRO"));
    }
}
