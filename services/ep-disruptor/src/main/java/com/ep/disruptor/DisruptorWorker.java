package com.ep.disruptor;
import com.lmax.disruptor.*;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.disruptor.util.DaemonThreadFactory;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 * D-171: Reactivated for data-platform ingest pipeline (SDD §5.5 Worker
 * Separation Pattern). Pre-allocated RingBuffer worker supporting
 * tryNext()-based backpressure and Pulsar degradation fallback.
 */
public final class DisruptorWorker<T> {
    private static final Logger log = LoggerFactory.getLogger(DisruptorWorker.class);
    private final Disruptor<T> disruptor;
    private final EventTranslatorOneArg<T, T> translator;
    private final int bufferSize;
    private final String name;
    private final AtomicLong published = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong consumed = new AtomicLong();

    private DisruptorWorker(Builder<T> b) {
        this.name = b.name; this.bufferSize = b.bufferSize; this.translator = b.translator;
        this.disruptor = new Disruptor<>(b.factory, b.bufferSize, DaemonThreadFactory.INSTANCE, b.producerType, b.waitStrategy.toWaitStrategy());
        EventHandler<T> counting = (evt, seq, eob) -> consumed.incrementAndGet();
        disruptor.handleEventsWith(counting).then(b.handler);
    }

    @PostConstruct public void start() { disruptor.start(); }
    public PublishResult publish(T payload) {
        long seq;
        try { seq = disruptor.getRingBuffer().tryNext(); }
        catch (InsufficientCapacityException e) { dropped.incrementAndGet(); return PublishResult.DROPPED; }
        try {
            // Note: Disruptor 4.0.0 RingBuffer has no publishEvent(translator, seq, payload) variant.
            // Reserve-via-tryNext path: get the pre-allocated slot, let the user's translator
            // populate it, then publish the reserved sequence (drop-on-full is already handled above).
            T slot = disruptor.getRingBuffer().get(seq);
            translator.translateTo(slot, seq, payload);
            disruptor.getRingBuffer().publish(seq);
            published.incrementAndGet();
            return PublishResult.PUBLISHED;
        } finally { }
    }
    @PreDestroy public void shutdown() { disruptor.shutdown(); }
    public DisruptorStats stats() {
        return new DisruptorStats(name, bufferSize, published.get(), dropped.get(), consumed.get());
    }

    public static <T> Builder<T> builder() { return new Builder<>(); }
    public static final class Builder<T> {
        private String name = "default"; private int bufferSize = 65536;
        private ProducerType producerType = ProducerType.MULTI;
        private WaitStrategyType waitStrategy = WaitStrategyType.BLOCKING;
        private EventFactory<T> factory; private EventTranslatorOneArg<T,T> translator; private EventHandler<T> handler;
        public Builder<T> name(String n){this.name=n;return this;}
        public Builder<T> bufferSize(int s){this.bufferSize=s;return this;}
        public Builder<T> producerType(ProducerType p){this.producerType=p;return this;}
        public Builder<T> waitStrategy(WaitStrategyType w){this.waitStrategy=w;return this;}
        public Builder<T> factory(EventFactory<T> f){this.factory=f;return this;}
        public Builder<T> translator(EventTranslatorOneArg<T,T> t){this.translator=t;return this;}
        public Builder<T> handler(EventHandler<T> h){this.handler=h;return this;}
        public DisruptorWorker<T> build() {
            java.util.Objects.requireNonNull(factory); java.util.Objects.requireNonNull(translator); java.util.Objects.requireNonNull(handler);
            return new DisruptorWorker<>(this);
        }
    }
}
