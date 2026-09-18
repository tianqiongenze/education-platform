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
 *
 * D-216 hardening (from the Java-wide audit):
 * <ul>
 *   <li>Exception handler: a failing handler no longer kills the consuming
 *       thread (the Disruptor default FatalExceptionHandler rethrows and
 *       permanently poisons the ring buffer). Events are logged-and-skipped
 *       so one poison payload cannot stall a whole pipeline.</li>
 *   <li>Single consumer chain: the counting hook is composed into the
 *       business handler instead of chaining a second handler behind it
 *       (which needlessly doubled consumer threads and serialized the
 *       business handler behind a pure counter).</li>
 *   <li>publish() guarantees the reserved sequence is always published —
 *       even when the translator throws — so a bad producer cannot leave
 *       a permanently unconsumable slot.</li>
 * </ul>
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
    private final AtomicLong failed = new AtomicLong();

    private DisruptorWorker(Builder<T> b) {
        this.name = b.name; this.bufferSize = b.bufferSize; this.translator = b.translator;
        this.disruptor = new Disruptor<>(b.factory, b.bufferSize, DaemonThreadFactory.INSTANCE, b.producerType, b.waitStrategy.toWaitStrategy());

        // Count + delegate in ONE handler: no second consumer thread, and
        // consumed stays accurate for throughput/backlog metrics.
        EventHandler<T> countingDelegate = (evt, seq, eob) -> {
            try {
                b.handler.onEvent(evt, seq, eob);
            } finally {
                consumed.incrementAndGet();
            }
        };
        // One poison event must never kill the consumer thread (default
        // FatalExceptionHandler does exactly that). Log, count, move on.
        ExceptionHandler<T> skipAndLog = new ExceptionHandler<>() {
            @Override public void handleEventException(Throwable ex, long sequence, T event) {
                failed.incrementAndGet();
                log.error("[disruptor] {} handler error at seq={} (event skipped): {}", name, sequence, ex.getMessage(), ex);
            }
            @Override public void handleOnStartException(Throwable ex) {
                log.error("[disruptor] {} consumer start error: {}", name, ex.getMessage(), ex);
            }
            @Override public void handleOnShutdownException(Throwable ex) {
                log.warn("[disruptor] {} consumer shutdown error: {}", name, ex.getMessage());
            }
        };
        disruptor.setDefaultExceptionHandler(skipAndLog);
        disruptor.handleEventsWith(countingDelegate);
    }

    @PostConstruct public void start() { disruptor.start(); }
    public PublishResult publish(T payload) {
        long seq;
        try { seq = disruptor.getRingBuffer().tryNext(); }
        catch (InsufficientCapacityException e) { dropped.incrementAndGet(); return PublishResult.DROPPED; }
        boolean ok = false;
        try {
            // Note: Disruptor 4.0.0 RingBuffer has no publishEvent(translator, seq, payload) variant.
            // Reserve-via-tryNext path: get the pre-allocated slot, let the user's translator
            // populate it, then publish the reserved sequence (drop-on-full is already handled above).
            T slot = disruptor.getRingBuffer().get(seq);
            try {
                translator.translateTo(slot, seq, payload);
            } catch (RuntimeException ex) {
                // translator failed: the slot may hold stale data, but the
                // reserved sequence MUST still be published — leaving it
                // unpublished makes every later slot permanently unconsumable.
                dropped.incrementAndGet();
                log.warn("[disruptor] {} translator error at seq={} (publishing stale slot to avoid poisoning): {}",
                        name, seq, ex.getMessage());
            }
            disruptor.getRingBuffer().publish(seq);
            published.incrementAndGet();
            return PublishResult.PUBLISHED;
        } finally { }
    }
    @PreDestroy public void shutdown() { disruptor.shutdown(); }
    public DisruptorStats stats() {
        return new DisruptorStats(name, bufferSize, published.get(), dropped.get(), consumed.get());
    }
    /** D-216: count of events whose handler threw (poison events skipped). */
    public long failedCount() { return failed.get(); }

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
