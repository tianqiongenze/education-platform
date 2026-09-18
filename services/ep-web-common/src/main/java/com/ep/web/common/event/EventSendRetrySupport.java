package com.ep.web.common.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Producer-side reliability helper for best-effort event publishing
 * (D-event-shared, gap "生产者可靠性").
 * <p>
 * Wraps a single fire-and-forget send action (e.g. Pulsar
 * {@code producer.sendAsync(payload)}) with:
 * <ul>
 *   <li><b>bounded retries</b> — on failure the action is re-invoked up to
 *       {@code maxRetries} times with linear backoff
 *       ({@code backoffMillis * attempt}) scheduled on
 *       {@link CompletableFuture#delayedExecutor(long, TimeUnit, Executor)};</li>
 *   <li><b>structured failure logging</b> — after the last attempt the event is
 *       logged at WARN with the structured fields {@code eventId},
 *       {@code eventType}, {@code aggregateId}, {@code schemaVersion} so an
 *       operator (or log pipeline) can reconstruct exactly what was lost;</li>
 *   <li><b>outbox hook</b> — the {@link EventFailureHandler} seam is where a
 *       future transactional-outbox implementation plugs in: instead of only
 *       logging, the handler will persist the event for reliable re-delivery.
 *       Until then the default handler only logs. Publishers must keep calling
 *       this helper <i>after</i> the business transaction commits (post-commit
 *       publish stage), never inside it.</li>
 * </ul>
 * The helper never throws: all failures end in the handler. It is transport
 * agnostic (works on any {@link CompletableFuture}-returning send action) so
 * ep-web-common needs no Pulsar dependency.
 */
public final class EventSendRetrySupport {

    private static final Logger log = LoggerFactory.getLogger(EventSendRetrySupport.class);

    /** Default retry count: 2 retries after the initial attempt (3 sends total). */
    public static final int DEFAULT_MAX_RETRIES = 2;

    /** Default linear backoff base: 200ms, 400ms, … */
    public static final long DEFAULT_BACKOFF_MILLIS = 200L;

    /** Logs the dropped event at WARN with structured fields. Future outbox: persist here instead. */
    public static final EventFailureHandler LOG_ONLY_FAILURE_HANDLER =
            (event, error, attempts) -> log.warn(
                    "event publish failed after {} attempt(s), event dropped (best-effort): "
                            + "eventId={} eventType={} aggregateId={} schemaVersion={} error={}",
                    attempts, event.eventId(), event.eventType(), event.aggregateId(),
                    event.schemaVersion(), error == null ? "unknown" : error.toString());

    private EventSendRetrySupport() {
    }

    /** A single send attempt; may throw synchronously (e.g. producer closed). */
    @FunctionalInterface
    public interface EventSendAction {
        CompletableFuture<?> send() throws Exception;
    }

    /** Terminal-failure seam: log today, transactional-outbox persist tomorrow. */
    @FunctionalInterface
    public interface EventFailureHandler {
        void onDropped(DomainEvent event, Throwable error, int attempts);
    }

    /**
     * Send with {@link #DEFAULT_MAX_RETRIES} retries, {@link #DEFAULT_BACKOFF_MILLIS}
     * backoff and the {@link #LOG_ONLY_FAILURE_HANDLER} failure handler.
     *
     * @return future completing {@code true} once delivered, {@code false} when
     *         all attempts failed (never completes exceptionally).
     */
    public static CompletableFuture<Boolean> sendWithRetry(EventSendAction action, DomainEvent event) {
        return sendWithRetry(action, event, DEFAULT_MAX_RETRIES, DEFAULT_BACKOFF_MILLIS);
    }

    /**
     * Send with configurable retries/backoff and the {@link #LOG_ONLY_FAILURE_HANDLER}
     * failure handler.
     */
    public static CompletableFuture<Boolean> sendWithRetry(EventSendAction action, DomainEvent event,
                                                           int maxRetries, long backoffMillis) {
        return sendWithRetry(action, event, maxRetries, backoffMillis, LOG_ONLY_FAILURE_HANDLER);
    }

    /**
     * Send with configurable retries, backoff and failure handler.
     *
     * @param maxRetries    number of retries after the initial attempt (&ge; 0)
     * @param backoffMillis linear backoff base; retry N waits {@code backoffMillis * N}
     * @param failureHandler invoked once when all attempts failed (outbox hook)
     */
    public static CompletableFuture<Boolean> sendWithRetry(EventSendAction action, DomainEvent event,
                                                           int maxRetries, long backoffMillis,
                                                           EventFailureHandler failureHandler) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        if (event == null || action == null || failureHandler == null) {
            result.complete(false);
            return result;
        }
        attempt(action, event, 0, Math.max(0, maxRetries), backoffMillis, failureHandler, result);
        return result;
    }

    private static void attempt(EventSendAction action, DomainEvent event, int attempt,
                                int maxRetries, long backoffMillis,
                                EventFailureHandler failureHandler, CompletableFuture<Boolean> result) {
        CompletableFuture<?> future;
        try {
            future = action.send();
        } catch (Exception syncFailure) {
            onFailure(action, event, attempt, maxRetries, backoffMillis, failureHandler, result, syncFailure);
            return;
        }
        if (future == null) {
            onFailure(action, event, attempt, maxRetries, backoffMillis, failureHandler, result,
                    new IllegalStateException("send action returned null"));
            return;
        }
        future.whenComplete((ignored, ex) -> {
            if (ex == null) {
                if (attempt > 0) {
                    log.info("event publish recovered on attempt {}: eventId={} eventType={} aggregateId={}",
                            attempt + 1, event.eventId(), event.eventType(), event.aggregateId());
                }
                result.complete(true);
            } else {
                Throwable cause = (ex instanceof CompletionException && ex.getCause() != null) ? ex.getCause() : ex;
                onFailure(action, event, attempt, maxRetries, backoffMillis, failureHandler, result, cause);
            }
        });
    }

    private static void onFailure(EventSendAction action, DomainEvent event, int attempt,
                                  int maxRetries, long backoffMillis,
                                  EventFailureHandler failureHandler, CompletableFuture<Boolean> result,
                                  Throwable error) {
        if (attempt < maxRetries) {
            long delay = backoffMillis * (attempt + 1L);
            log.warn("event publish attempt {}/{} failed, retrying in {}ms: eventId={} eventType={} aggregateId={} error={}",
                    attempt + 1, maxRetries + 1, delay, event.eventId(), event.eventType(),
                    event.aggregateId(), error.toString());
            CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS)
                    .execute(() -> attempt(action, event, attempt + 1, maxRetries, backoffMillis,
                            failureHandler, result));
        } else {
            failureHandler.onDropped(event, error, attempt + 1);
            result.complete(false);
        }
    }
}
