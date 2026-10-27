package com.ep.payment.route;

import com.ep.payment.api.PaymentChannel;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * D-237/A2: channel descriptor — one row per registered payment channel
 * (「AI支付」文档 · 路由子系统: "通道信息、通道成本、分组规则、行业准入、规则链").
 *
 * @param channelId      unique channel key (matches PaymentChannel enum name for
 *                       SPI lookup)
 * @param displayName    human-readable name
 * @param costBps        per-transaction cost in basis points (e.g. 60 = 0.6%) —
 *                       the routing cost signal AI advisors optimize
 * @param healthScore    rolling success-rate 0..100 (updated by callbacks/failures)
 * @param priority       lower = preferred when cost and health tie
 * @param currency       ISO-4217 code this channel settles in
 * @param minAmount      minimum ticket size (纳米级微支付: sub-cent channels set a
 *                       tiny min; card rails set a higher one)
 * @param maxAmount      maximum ticket size
 * @param enabled        on/off switch (运营开关)
 * @param registeredAt   registration instant
 */
public record ChannelDescriptor(
        String channelId,
        String displayName,
        int costBps,
        int healthScore,
        int priority,
        String currency,
        BigDecimal minAmount,
        BigDecimal maxAmount,
        boolean enabled,
        Instant registeredAt) {

    public ChannelDescriptor {
        Objects.requireNonNull(channelId, "channelId");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(minAmount, "minAmount");
        Objects.requireNonNull(maxAmount, "maxAmount");
        if (costBps < 0 || healthScore < 0 || healthScore > 100) {
            throw new IllegalArgumentException("costBps must be >=0 and healthScore in 0..100");
        }
    }

    public boolean supports(String reqCurrency, BigDecimal amount) {
        return enabled
                && currency.equals(reqCurrency)
                && amount.compareTo(minAmount) >= 0
                && amount.compareTo(maxAmount) <= 0;
    }
}
