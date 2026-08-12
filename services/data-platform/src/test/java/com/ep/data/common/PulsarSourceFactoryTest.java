package com.ep.data.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PulsarSourceFactoryTest {

    @Test
    void createConsumerConfig_returnsExpectedFields() {
        PulsarConsumerConfig cfg = PulsarSourceFactory.createConsumerConfig(
                "persistent://public/cdc/order_group", "sub-order", "AVRO");
        assertEquals("persistent://public/cdc/order_group", cfg.getTopic());
        assertEquals("sub-order", cfg.getSubscription());
        assertEquals("AVRO", cfg.getSchemaType());
        assertEquals(PulsarConsumerConfig.DEFAULT_RECEIVE_TIMEOUT_MS, cfg.getReceiveTimeoutMs());
    }

    @Test
    void createConsumerConfig_defaultsToAvroWhenBlank() {
        PulsarConsumerConfig cfg = PulsarSourceFactory.createConsumerConfig(
                "persistent://public/cdc/order_group", "sub", null);
        assertEquals("AVRO", cfg.getSchemaType());
    }

    @Test
    void createConsumerConfig_uppercasesSchemaType() {
        PulsarConsumerConfig cfg = PulsarSourceFactory.createConsumerConfig(
                "persistent://public/cdc/order_group", "sub", "avro");
        assertEquals("AVRO", cfg.getSchemaType());
    }

    @Test
    void createConsumerConfig_throwsOnBlankTopic() {
        assertThrows(IllegalArgumentException.class,
                () -> PulsarSourceFactory.createConsumerConfig("", "sub", "AVRO"));
    }

    @Test
    void createConsumerConfig_throwsOnBlankSubscription() {
        assertThrows(IllegalArgumentException.class,
                () -> PulsarSourceFactory.createConsumerConfig("persistent://public/cdc/x", " ", "AVRO"));
    }
}
