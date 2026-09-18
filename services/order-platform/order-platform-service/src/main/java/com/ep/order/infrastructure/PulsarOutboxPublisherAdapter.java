package com.ep.order.infrastructure;

import com.ep.order.event.OutboxPublisher;
import org.springframework.stereotype.Component;

/**
 * D-217: Infrastructure adapter that implements the {@link OutboxPublisher}
 * port by delegating to {@link PulsarEventPublisher#publishRaw}.
 * <p>
 * Living in the {@code infrastructure} package (which already depends on
 * {@code event} for {@code DomainEvent}), this adapter keeps the dependency
 * direction one-way: {@code infrastructure → event}. The relay in
 * {@code event} depends only on the {@link OutboxPublisher} interface, so
 * there is no cycle.
 */
@Component
public class PulsarOutboxPublisherAdapter implements OutboxPublisher {

    private final PulsarEventPublisher pulsarEventPublisher;

    public PulsarOutboxPublisherAdapter(PulsarEventPublisher pulsarEventPublisher) {
        this.pulsarEventPublisher = pulsarEventPublisher;
    }

    @Override
    public void publishRaw(String eventType, String json) throws Exception {
        pulsarEventPublisher.publishRaw(eventType, json);
    }
}
