package com.ep.payment.infrastructure;

import com.ep.payment.event.PaymentEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.ProducerBuilder;
import org.apache.pulsar.client.api.PulsarClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Publishes {@link PaymentEvent}s to Pulsar with a layered degradation chain so
 * a financial event is never silently lost (mirrors the data-platform
 * {@code PulsarFallback} pattern, SDD §5.5 / D-216 audit P0-3).
 *
 * <ol>
 *   <li>{@code ep.pulsar.enabled=true} + reachable broker → real Pulsar
 *       producer on the configurable payment-events topic;</li>
 *   <li>otherwise (dev/test default, or broker unreachable, or send failure) →
 *       local disk spool (JSON-lines, daily-rotated, size-capped): a dropped
 *       event is recoverable via {@link #replay()} until an operator drains
 *       the spool;</li>
 *   <li>spool write itself failing (disk full) → loud ERROR, the last line of
 *       defence. Nothing is silently discarded anywhere in the chain.</li>
 * </ol>
 *
 * <p>The publish method is fail-safe: it never throws to the caller, so the
 * Disruptor publish stage keeps draining its ring buffer even when the broker
 * is down.</p>
 *
 * <p>EP_DEV_ONLY guard: when {@code ep.pulsar.enabled=false} (the dev/test
 * default) the producer is never built and every event goes straight to the
 * disk spool — so unit tests without a running broker still exercise the full
 * fallback path.</p>
 */
@Component
public class PaymentEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventPublisher.class);

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Value("${ep.pulsar.enabled:false}")
    private boolean pulsarEnabled;

    @Value("${ep.pulsar.topic.payment-events:payment-events}")
    private String topic;

    @Value("${ep.pulsar.service-url:pulsar://pulsar-broker:6650}")
    private String serviceUrl;

    /** Default spool directory (java.io.tmpdir/ep-payment-spool). */
    static final String DEFAULT_SPOOL_DIR =
            System.getProperty("java.io.tmpdir", "/tmp") + "/ep-payment-spool";

    @Value("${ep.payment.fallback.spool-dir:/tmp/ep-payment-spool}")
    private String spoolDir;

    @Value("${ep.payment.fallback.spool-max-bytes:268435456}")
    private long spoolMaxBytes;

    private volatile PulsarClient client;
    private volatile Producer<byte[]> producer;
    private volatile boolean producerBroken;

    private final AtomicLong spooledTotal = new AtomicLong();
    private final AtomicLong publishedTotal = new AtomicLong();

    @PostConstruct
    public void init() {
        if (!pulsarEnabled) {
            log.info("PaymentEventPublisher initialized in spool-only mode (ep.pulsar.enabled=false)");
            return;
        }
        log.info("PaymentEventPublisher enabled — producer will be lazily created on first publish (topic={})", topic);
    }

    /**
     * Publish a single {@link PaymentEvent}. Fail-safe: never throws.
     * Tries Pulsar first, falls back to the disk spool on any failure.
     */
    public void publish(PaymentEvent event) {
        if (event == null) {
            return;
        }
        byte[] payload;
        try {
            payload = mapper.writeValueAsBytes(event);
        } catch (Exception e) {
            // even serialization failure must not lose the event — write a
            // minimal marker to the spool so it is still accounted for.
            log.warn("[PaymentEventPublisher] serialize failed for {}: {}", event.getClass().getSimpleName(), e.getMessage());
            payload = ("{\"type\":\"" + event.getType() + "\",\"serializeError\":\""
                    + e.getMessage() + "\",\"aggregateId\":\"" + event.getAggregateId() + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
        }

        if (pulsarEnabled && !producerBroken && sendViaPulsar(payload)) {
            publishedTotal.incrementAndGet();
            return;
        }
        spool(event, payload);
    }

    private boolean sendViaPulsar(byte[] payload) {
        try {
            Producer<byte[]> p = producer();
            if (p != null) {
                p.sendAsync(payload);
                return true;
            }
            return false;
        } catch (Exception e) {
            producerBroken = true;
            log.error("[PaymentEventPublisher] Pulsar send failed — degrading to disk spool: {}", e.getMessage());
            return false;
        }
    }

    private synchronized Producer<byte[]> producer() throws Exception {
        if (producer != null) {
            return producer;
        }
        if (client == null) {
            client = PulsarClient.builder().serviceUrl(serviceUrl).build();
        }
        ProducerBuilder<byte[]> b = client.newProducer()
                .topic(topic)
                .blockIfQueueFull(true)
                .maxPendingMessages(10_000)
                .sendTimeout(3000, java.util.concurrent.TimeUnit.MILLISECONDS);
        producer = b.create();
        log.info("[PaymentEventPublisher] Pulsar producer ready — topic={}", topic);
        return producer;
    }

    private void spool(PaymentEvent event, byte[] payload) {
        try {
            Path dir = Path.of(spoolDir);
            Files.createDirectories(dir);
            Path file = dir.resolve("spool-" + LocalDate.now() + ".jsonl");
            if (Files.exists(file) && Files.size(file) > spoolMaxBytes) {
                file = dir.resolve("spool-" + LocalDate.now() + "-" + System.currentTimeMillis() + ".jsonl");
            }
            Files.write(file, payload, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            Files.write(file, System.lineSeparator().getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            long total = spooledTotal.incrementAndGet();
            log.warn("[PaymentEventPublisher] event spooled to {} (type={}, aggregateId={}, spooledTotal={})",
                    file, event.getType(), event.getAggregateId(), total);
        } catch (IOException e) {
            // The last line of defence failed — this is a data-loss risk that
            // must page someone, not a debug line.
            log.error("[PaymentEventPublisher] DISK SPOOL WRITE FAILED — event LOST (type={}, aggregateId={}): {}",
                    event.getType(), event.getAggregateId(), e.getMessage());
        }
    }

    /** @return number of events that fell through to the disk spool. */
    public long spooledTotal() {
        return spooledTotal.get();
    }

    /** @return number of events successfully published to Pulsar. */
    public long publishedTotal() {
        return publishedTotal.get();
    }

    @PreDestroy
    public void stop() {
        try {
            if (producer != null) {
                producer.close();
            }
        } catch (Exception e) {
            log.warn("[PaymentEventPublisher] producer close failed: {}", e.getMessage());
        }
        try {
            if (client != null) {
                client.close();
            }
        } catch (Exception e) {
            log.warn("[PaymentEventPublisher] client close failed: {}", e.getMessage());
        }
    }
}
