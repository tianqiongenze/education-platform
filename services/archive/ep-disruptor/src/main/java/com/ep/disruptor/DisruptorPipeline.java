package com.ep.disruptor;
import com.lmax.disruptor.*;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.disruptor.util.DaemonThreadFactory;
import java.util.*;

/**
 * @deprecated D-094 (2026-08-08): ep-disruptor has 0 consumers across the
 * monorepo — no service imports {@code com.ep.disruptor.*}, no
 * {@code pom.xml} declares a dependency, and the artifact is not in
 * {@code .m2}. Deprecated in place (NOT deleted) for future reuse.
 * See {@code services/ep-disruptor/DEPRECATED.md}.
 */
// TODO D-120: Remove after migration complete. Verified zero callers.
@Deprecated(forRemoval = false, since = "D-094")
public final class DisruptorPipeline {
    private final Map<String, DisruptorWorker<?>> stages = new LinkedHashMap<>();
    private final String name;
    private DisruptorPipeline(String n) { this.name = n; }
    public static DisruptorPipeline named(String n) { return new DisruptorPipeline(n); }

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
        // 转发到下一阶段
        List<String> names = new ArrayList<>(stages.keySet());
        int idx = names.indexOf(fromStage);
        if (idx + 1 < names.size()) ((DisruptorWorker<T>) stages.get(names.get(idx+1))).publish(payload);
    }
    public void shutdown() {
        // 逆序 shutdown，确保上游先停
        List<DisruptorWorker<?>> rev = new ArrayList<>(stages.values());
        Collections.reverse(rev);
        rev.forEach(DisruptorWorker::shutdown);
    }
    public DisruptorStats stats(int stageIdx) { return new ArrayList<>(stages.values()).get(stageIdx).stats(); }
}
