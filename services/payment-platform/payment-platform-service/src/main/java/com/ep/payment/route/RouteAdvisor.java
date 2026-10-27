package com.ep.payment.route;

import com.ep.payment.api.PaymentRequest;

import java.util.Optional;

/**
 * D-237/A2: AI route advisor hook (「AI支付」文档 · "路由选哪条通道，AI 可以实时算
 * 成本和成功率……模型给建议，规则做闸门").
 *
 * <p>Implementations (model-backed) may suggest a channel. The
 * {@link PaymentChannelRouter} treats the suggestion as a <b>hint only</b> — the
 * final decision always passes the deterministic rule gate (currency/amount
 * support, enabled flag, health floor). A missing or unregistered suggestion is
 * silently ignored, never fatal.
 *
 * <p>This keeps the probability/model world and the deterministic/rule world
 * separated by the gate the reference architecture requires.
 */
public interface RouteAdvisor {

    /**
     * @param request the incoming payment request
     * @return suggested channelId, or empty when the model has no opinion
     */
    Optional<String> suggestChannel(PaymentRequest request);
}
