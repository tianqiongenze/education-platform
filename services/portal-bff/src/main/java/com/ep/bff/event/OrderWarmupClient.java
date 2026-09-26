package com.ep.bff.event;

/**
 * Port for order-detail cache warm-up (D-228: event consumer matrix).
 * <p>
 * Implemented by {@link HttpOrderWarmupClient}, which triggers the
 * order-platform order-detail endpoint so its L1/L2 cache (ep-cache
 * {@code CACHE_ORDER} in {@code OrderServiceImpl#getOrderById}) fills before
 * the first user request arrives. Kept as an interface so the
 * {@link OrderPaidConsumer} can be unit-tested with a Mockito mock
 * (pure JUnit + Mockito, no Spring context, no broker).
 */
public interface OrderWarmupClient {

    /**
     * Best-effort warm-up of the order-detail cache. Never throws; failures
     * are logged by the implementation.
     *
     * @param orderId paid order id
     */
    void warmupOrderDetail(long orderId);
}
