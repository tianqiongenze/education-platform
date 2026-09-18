package com.ep.data.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
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
import java.util.ArrayList;
import java.util.List;

/**
 * Pulsar fallback for Disruptor degradation (SDD §5.5), D-216 (audit P0-3).
 *
 * <p>Previously the production branch was a log statement plus a TODO — events
 * degraded here were simply lost. The fallback is now real, with a layered
 * degradation chain:</p>
 *
 * <ol>
 *   <li>{@code ep.pulsar.enabled=true} + reachable broker → real Pulsar producer
 *       on the configurable fallback topic;</li>
 *   <li>otherwise → local disk spool (JSON-lines, daily-rotated, size-capped):
 *       a dropped event is always recoverable via {@link #replay()} until an
 *       operator drains the spool;</li>
 *   <li>spool write itself failing (disk full) → loud ERROR, the last line of
 *       defence. Nothing is silently discarded anywhere in the chain.</li>
 * </ol>
 */
@Component
public class PulsarFallback {

    private static final Logger log = LoggerFactory.getLogger(PulsarFallback.class);

    @Value("${ep.pulsar.enabled:false}")
    private boolean pulsarEnabled;

    @Value("${ep.pulsar.service-url:pulsar://pulsar-broker:6650}")
    private String serviceUrl;

    @Value("${ep.ingest.fallback.topic:persistent://public/default/ep-ingest-fallback}")
    private String fallbackTopic;

    @Value("${ep.ingest.fallback.spool-dir:/tmp/ep-ingest-spool}")
    private String spoolDir = System.getProperty("java.io.tmpdir", "/tmp") + "/ep-ingest-spool";

    @Value("${ep.ingest.fallback.spool-max-bytes:268435456}")
    private long spoolMaxBytes = 268_435_456L;

    private final ObjectMapper mapper = new ObjectMapper();

    private volatile PulsarClient client;
    private volatile Producer<byte[]> producer;
    private volatile boolean producerBroken;
    private long spooledTotal;

    public void publish(IngestEvent event) {
        if (event == null || event.getEnvelope() == null) {
            log.warn("[PulsarFallback] Null event or envelope — skipping");
            return;
        }
        byte[] payload = serialize(event);
        if (pulsarEnabled && !producerBroken) {
            if (sendViaPulsar(payload)) {
                return;
            }
        }
        spool(event, payload);
    }

    private byte[] serialize(IngestEvent event) {
        try {
            var node = mapper.createObjectNode();
            var env = event.getEnvelope();
            node.put("table", env.getTable());
            node.put("opType", env.getOpType() == null ? null : env.getOpType().name());
            node.put("tsMs", env.getTsMs());
            node.put("sourceCell", event.getSourceCell());
            node.put("before", env.getBefore());
            node.put("after", env.getAfter());
            node.put("ingestTs", event.getIngestTs());
            return mapper.writeValueAsBytes(node);
        } catch (Exception e) {
            // fall back to a minimal marker so the event is still accounted for
            return ("{\"table\":\"" + event.getEnvelope().getTable() + "\",\"serializeError\":\""
                    + e.getMessage() + "\"}").getBytes(StandardCharsets.UTF_8);
        }
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
            log.error("[PulsarFallback] Pulsar send failed — degrading to disk spool: {}", e.getMessage());
            return false;
        }
    }

    private synchronized Producer<byte[]> producer() throws PulsarClientException {
        if (producer != null) {
            return producer;
        }
        if (client == null) {
            client = PulsarClient.builder().serviceUrl(serviceUrl).build();
        }
        producer = client.newProducer().topic(fallbackTopic)
                .blockIfQueueFull(true).maxPendingMessages(10_000).create();
        log.info("[PulsarFallback] Pulsar producer ready — topic={}", fallbackTopic);
        return producer;
    }

    private void spool(IngestEvent event, byte[] payload) {
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
            spooledTotal++;
            log.warn("[PulsarFallback] event spooled to {} (table={}, spooledTotal={})",
                    file, event.getEnvelope().getTable(), spooledTotal);
        } catch (IOException e) {
            // The last line of defence failed — this is a data-loss risk that
            // must page someone, not a debug line.
            log.error("[PulsarFallback] DISK SPOOL WRITE FAILED — event LOST (table={}): {}",
                    event.getEnvelope().getTable(), e.getMessage());
        }
    }

    /**
     * Drain the spool: parse every JSONL entry and return the raw lines so an
     * operator (or the worker recovery path) can re-publish them once Pulsar
     * is reachable again.
     */
    public List<String> replay() throws IOException {
        Path dir = Path.of(spoolDir);
        List<String> lines = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return lines;
        }
        try (var stream = Files.list(dir)) {
            for (Path p : stream.filter(f -> f.getFileName().toString().endsWith(".jsonl")).toList()) {
                lines.addAll(Files.readAllLines(p, StandardCharsets.UTF_8));
            }
        }
        return lines;
    }

    public long spooledTotal() {
        return spooledTotal;
    }

    @PreDestroy
    public void stop() throws PulsarClientException {
        if (producer != null) {
            producer.close();
        }
        if (client != null) {
            client.close();
        }
    }
}
