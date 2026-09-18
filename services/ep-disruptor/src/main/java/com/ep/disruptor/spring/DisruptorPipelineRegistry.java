package com.ep.disruptor.spring;

import com.ep.disruptor.DisruptorPipeline;
import com.ep.disruptor.DisruptorStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * D-216: single owner of pipeline lifecycle for a Spring application.
 *
 * <ul>
 *   <li>starts every registered {@link DisruptorPipeline} in registration
 *       order during the SmartLifecycle ramp-up;</li>
 *   <li>stops them in <b>reverse</b> order on shutdown so upstream stages
 *       drain before their downstream consumers vanish;</li>
 *   <li>aggregates {@link DisruptorStats} for actuator/metrics exposure.</li>
 * </ul>
 *
 * Services simply declare their pipelines as beans — the registry does the
 * rest. This is what makes the multi-level scheduling queue a true shared,
 * plug-and-play primitive across all Java services.
 */
public class DisruptorPipelineRegistry implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(DisruptorPipelineRegistry.class);

    private final Map<String, DisruptorPipeline> pipelines = new LinkedHashMap<>();
    private volatile boolean running;

    public synchronized DisruptorPipelineRegistry register(DisruptorPipeline pipeline) {
        pipelines.put(pipeline.name(), pipeline);
        if (running) {
            // late registration (e.g. lazy beans): start immediately
            pipeline.start();
        }
        return this;
    }

    @Override
    public synchronized void start() {
        pipelines.values().forEach(p -> {
            p.start();
            log.info("[disruptor] pipeline '{}' started", p.name());
        });
        running = true;
    }

    @Override
    public synchronized void stop() {
        running = false;
        // D-216: stop in REGISTRATION order — each pipeline drains its own
        // stages upstream-first (DisruptorPipeline.shutdown declaration order);
        // reverse here would stop downstream pipelines before their producers.
        pipelines.values().forEach(p -> {
            p.shutdown();
            log.info("[disruptor] pipeline '{}' shut down", p.name());
        });
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        // start late (after infra beans), stop early — before web server refuses traffic
        return SmartLifecycle.DEFAULT_PHASE - 1000;
    }

    public Collection<DisruptorPipeline> pipelines() {
        return pipelines.values();
    }

    /** pipeline -> stage -> stats, for actuator/metrics exposure. */
    public Map<String, Map<String, DisruptorStats>> statsSnapshot() {
        Map<String, Map<String, DisruptorStats>> out = new LinkedHashMap<>();
        pipelines.forEach((name, p) -> out.put(name, p.statsAll()));
        return out;
    }
}
