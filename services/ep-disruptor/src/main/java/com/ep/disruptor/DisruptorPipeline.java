package com.ep.disruptor;
import com.lmax.disruptor.*;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.disruptor.util.DaemonThreadFactory;
import java.util.*;

/**
 * D-171: Reactivated for data-platform ingest pipeline (SDD §5.5 Worker
 * Separation Pattern). Multi-stage Disruptor pipeline for high-frequency
 * write paths with ordered stage forwarding.
 */
public final class DisruptorPipeline {
    private final Map<String, DisruptorWorker<?>> stages = new LinkedHashMap<>();
    private final String name;
    private DisruptorPipeline(String n) { this.name = n; }
    public static DisruptorPipeline named(String n) { return new DisruptorPipeline(n); }

    /** D-216: pipeline name for registry keys and metrics tags. */
    public String name() { return name; }

    public <T> DisruptorPipeline stage(String stageName, EventFactory<T> factory,
            EventTranslatorOneArg<T,T> translator, EventHandler<T> handler) {
        return stage(stageName, 65536, ProducerType.MULTI, WaitStrategyType.BLOCKING, factory, translator, handler);
    }
    public <T> DisruptorPipeline stage(String stageName, int bufferSize, ProducerType pt,
            WaitStrategyType w, EventFactory<T> factory, EventTranslatorOneArg<T,T> translator, EventHandler<T> handler) {
        DisruptorWorker<T> wk = DisruptorWorker.<T>builder()
            .name(name+":"+stageName).bufferSize(bufferSize).producerType(pt).waitStrategy(w)
            .factory(factory).translator(translator).handler(handler).build();
        stages.put(stageName, wk); return this;
    }
    public void start() { stages.values().forEach(DisruptorWorker::start); }
    @SuppressWarnings("unchecked")
    public <T> PublishResult publish(String stage, T payload) {
        DisruptorWorker<T> w = (DisruptorWorker<T>) stages.get(stage);
        if (w == null) throw new IllegalArgumentException("unknown stage " + stage);
        return w.publish(payload);
    }
    @SuppressWarnings("unchecked")
    public <T> void forward(String fromStage, T payload) {
        // 转发到下一阶段；末级 forward 无下游（链尾），丢弃时 DisruptorWorker
        // 自身的 dropped 计数已记录（D-216: 不再静默）。
        List<String> names = new ArrayList<>(stages.keySet());
        int idx = names.indexOf(fromStage);
        if (idx < 0) throw new IllegalArgumentException("unknown stage " + fromStage);
        if (idx + 1 < names.size()) {
            ((DisruptorWorker<T>) stages.get(names.get(idx+1))).publish(payload);
        }
    }
    public void shutdown() {
        // D-216 (audit P2-1): stop in DECLARATION order — the upstream stage
        // stops first (draining its buffer); each downstream stage then drains
        // what its predecessor published before stopping itself.
        List<DisruptorWorker<?>> order = new ArrayList<>(stages.values());
        order.forEach(DisruptorWorker::shutdown);
    }
    public DisruptorStats stats(int stageIdx) { return new ArrayList<>(stages.values()).get(stageIdx).stats(); }

    /** D-216: stage names in declaration order (for metrics/actuator exposure). */
    public List<String> stageNames() { return new ArrayList<>(stages.keySet()); }

    /** D-216: stats of one stage by name; null when the stage is unknown. */
    public DisruptorStats stats(String stageName) {
        DisruptorWorker<?> w = stages.get(stageName);
        return w == null ? null : w.stats();
    }

    /** D-216: stats of every stage, keyed "stageName". */
    public Map<String, DisruptorStats> statsAll() {
        Map<String, DisruptorStats> out = new LinkedHashMap<>();
        stages.forEach((k, v) -> out.put(k, v.stats()));
        return out;
    }
}
