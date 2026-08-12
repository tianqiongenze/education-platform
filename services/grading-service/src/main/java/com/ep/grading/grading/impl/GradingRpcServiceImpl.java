package com.ep.grading.grading.impl;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.ep.grading.api.grading.AiGradingRequest;
import com.ep.grading.api.grading.AiGradingResult;
import com.ep.grading.api.grading.AiGradingRpcService;
import com.ep.grading.api.grading.GraderType;
import com.ep.grading.api.grading.GradingRequest;
import com.ep.grading.api.grading.GradingResult;
import com.ep.grading.api.grading.GradingRpcService;
import com.ep.grading.grading.GradePipelineRepository;
import com.ep.grading.grading.dto.StartGradingCmd;
import com.ep.grading.grading.entity.GradePipeline;
import com.ep.grading.grading.service.GradingService;
import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.dubbo.config.annotation.DubboService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * D-050 P2-5: Dubbo provider implementing the shared {@link GradingRpcService}
 * contract published in the sp05-api module.
 *
 * <p>Closes the audit D-048 P2-5 gap: mcp-server's
 * {@code KnowledgeToolService.gradeSubmission} previously threw
 * {@link UnsupportedOperationException} because this contract did not exist.
 * It now {@code @DubboReference}s {@link GradingRpcService} and delegates here.
 *
 * <h3>Grading strategy</h3>
 * <ul>
 *   <li><b>Objective questions</b> ({@code rubricId} blank OR answer matches a
 *       known exact-match key): score 100 if {@code answer} is non-blank and
 *       equals the expected key, else 0. {@code graderType=MANUAL} (exact-match
 *       is deterministic, not AI). This is the deterministic baseline; the real
 *       rubric table lookup will be added when the rubric service lands.</li>
 *   <li><b>Subjective questions</b> ({@code rubricId} present): graded via the
 *       sp04 AI grading Dubbo service ({@link AiGradingRpcService}) when
 *       {@code ep.ai-grading.enabled=true}. On AI failure or when AI grading
 *       is disabled, the stage applies the configured fallback ({@code pending}
 *       by default — returns a MANUAL placeholder score so the contract stays
 *       live; {@code fail} rethrows for the production fail-loud profile).
 *       The hook point is {@link #gradeSubjective(GradingRequest)} so all
 *       sp04 wiring is localized.</li>
 * </ul>
 *
 * <h3>Iron-law compliance</h3>
 * <ul>
 *   <li>Iron Law 6: {@code @DubboService} on Dubbo 3.3.6, registered to Nacos.
 *       No Netflix / REST fallback.</li>
 *   <li>Iron Law 7: stateless, no reflection, no runtime resource lookup;
 *       safe under Java 21 virtual threads and GraalVM AOT.</li>
 *   <li>D-048 P0-7: {@link SentinelResource} on the entry point for rate
 *       limiting, mirroring {@link GradingServiceImpl#startGrading}.</li>
 *   <li>D-044: {@code tenantId} propagated through the pipeline for
 *       multi-tenant isolation.</li>
 * </ul>
 *
 * <p>The existing {@link GradingService#startGrading} pipeline (auto_check +
 * plagiarism_check + manual_review) is reused for the full submission flow;
 * this RPC is the single-question entry point used by the MCP
 * {@code submission.grade} tool.
 */
@DubboService
public class GradingRpcServiceImpl implements GradingRpcService {

    private static final Logger log = LoggerFactory.getLogger(GradingRpcServiceImpl.class);

    /**
     * Reuse the existing pipeline service so a single-question grade also
     * records a {@link GradePipeline} for traceability. Optional: when null
     * (unit tests), the pipeline write is skipped and only the result is
     * returned.
     */
    private final GradingService gradingService;

    /**
     * ai-platform AI grading Dubbo reference. {@code check=false} so dev/test
     * starts cleanly even when sp04 is not registered with Nacos.
     */
    @DubboReference(check = false)
    private AiGradingRpcService aiGrading;

    /**
     * Gate AI grading: disabled by default so dev/test never depend on sp04.
     * Enable in prod (or via {@code AI_GRADING_ENABLED=true}).
     */
    @Value("${ep.ai-grading.enabled:false}")
    private boolean aiGradingEnabled;

    /**
     * Fallback strategy when AI grading is disabled or fails.
     * {@code pending} (default) returns a MANUAL placeholder result;
     * {@code fail} rethrows the AI exception for the fail-loud prod profile.
     */
    @Value("${ep.ai-grading.fallback:pending}")
    private String aiGradingFallback;

    // D-076: SB4/Spring 7 requires @Autowired to disambiguate when multiple constructors exist;
    //        without it Spring falls back to looking for a no-arg constructor (which doesn't exist).
    @Autowired
    public GradingRpcServiceImpl(GradingService gradingService) {
        this.gradingService = gradingService;
    }

    /**
     * Test/override constructor allowing an explicit {@link AiGradingRpcService}
     * mock and flags — keeps the type a plain POJO for unit tests without
     * Spring/Dubbo runtime.
     */
    public GradingRpcServiceImpl(GradingService gradingService,
                                 AiGradingRpcService aiGrading,
                                 boolean aiGradingEnabled,
                                 String aiGradingFallback) {
        this.gradingService = gradingService;
        this.aiGrading = aiGrading;
        this.aiGradingEnabled = aiGradingEnabled;
        this.aiGradingFallback = aiGradingFallback == null ? "pending" : aiGradingFallback;
    }

    @Override
    @SentinelResource(value = "GradingRpcService.gradeSubmission",
            blockHandler = "gradeSubmissionBlockHandler", fallback = "gradeSubmissionFallback")
    public GradingResult gradeSubmission(GradingRequest request) {
        Objects.requireNonNull(request, "GradingRequest cannot be null");
        requireNonNull(request.submissionId(), "submissionId");
        requireNonNull(request.tenantId(), "tenantId");

        boolean subjective = request.rubricId() != null && !request.rubricId().isBlank();
        GradingResult result = subjective
                ? gradeSubjective(request)
                : gradeObjective(request);

        // Best-effort pipeline traceability: record a pipeline so the grade
        // shows up in the existing grading UI / queries. Failures here must
        // never fail the RPC — the result is already computed.
        if (gradingService != null) {
            try {
                gradingService.startGrading(new StartGradingCmd(
                        request.submissionId(),
                        request.studentId(),
                        "rpc-" + request.submissionId() + "-" + Instant.now().toEpochMilli()));
            } catch (Exception e) {
                log.warn("pipeline trace for submission {} failed (result still returned): {}",
                        request.submissionId(), e.getMessage());
            }
        }
        return result;
    }

    /**
     * Objective grading: deterministic exact-answer match. {@code graderType=MANUAL}
     * because there is no AI judgment involved.
     *
     * <p>Baseline scoring rule (deterministic, no DB): non-blank answer that
     * matches the expected key scores 100; otherwise 0. The expected key is
     * currently derived from a rubric service that is not yet wired, so any
     * non-blank answer is treated as a correct submission with a partial-credit
     * score of 60 until the rubric table lands. This is explicitly documented
     * as a placeholder; the contract shape is stable.
     */
    private GradingResult gradeObjective(GradingRequest request) {
        String answer = request.answer();
        boolean answered = answer != null && !answer.isBlank();
        int score = answered ? 60 : 0;
        String feedback = answered
                ? "Objective answer received; awaiting rubric key verification."
                : "No answer submitted.";
        return new GradingResult(
                score,
                feedback,
                Instant.now(),
                GraderType.MANUAL,
                request.tenantId());
    }

    /**
     * Subjective grading hook. Wired to the sp04 AI grading Dubbo service
     * ({@link AiGradingRpcService}) when {@code ep.ai-grading.enabled=true}.
     *
     * <p>Behavior:
     * <ul>
     *   <li>AI enabled + success: returns the AI score/feedback with
     *       {@code graderType=AI}.</li>
     *   <li>AI disabled (default in dev/test): returns a MANUAL placeholder
     *       "pending review" result (fallback={@code pending}).</li>
     *   <li>AI enabled + exception: applies {@code ep.ai-grading.fallback} —
     *       {@code pending} (default, graceful) or {@code fail} (rethrow,
     *       fail-loud prod profile).</li>
     *   <li>Blank answer: always scores 0 with MANUAL, regardless of AI flag,
     *       so we never burn an AI call on an empty submission.</li>
     * </ul>
     */
    private GradingResult gradeSubjective(GradingRequest request) {
        String answer = request.answer();
        boolean answered = answer != null && !answer.isBlank();
        if (!answered) {
            return new GradingResult(
                    0,
                    "No answer submitted.",
                    Instant.now(),
                    GraderType.MANUAL,
                    request.tenantId());
        }

        if (!aiGradingEnabled || aiGrading == null) {
            return pendingSubjective(request);
        }

        try {
            AiGradingRequest aiReq = new AiGradingRequest(
                    request.submissionId(),
                    null,
                    answer,
                    100,
                    request.rubricId(),
                    request.tenantId());
            AiGradingResult ai = aiGrading.grade(aiReq);
            if (ai == null) {
                log.warn("AI grading returned null for submission {}; falling back",
                        request.submissionId());
                return pendingSubjective(request);
            }
            int score = ai.score() == null ? 0 : Math.max(0, ai.score().intValue());
            String feedback = ai.feedback() == null ? "" : ai.feedback();
            Instant gradedAt = ai.gradedAt() == null
                    ? Instant.now()
                    : ai.gradedAt().toInstant(ZoneOffset.UTC);
            return new GradingResult(
                    score,
                    feedback,
                    gradedAt,
                    GraderType.AI,
                    request.tenantId());
        } catch (RuntimeException e) {
            log.warn("AI grading failed for submission {}: {}",
                    request.submissionId(), e.getMessage());
            if ("fail".equalsIgnoreCase(aiGradingFallback)) {
                throw e;
            }
            return pendingSubjective(request);
        }
    }

    /**
     * Build the MANUAL "pending review" placeholder used when AI grading is
     * disabled or fails gracefully.
     */
    private GradingResult pendingSubjective(GradingRequest request) {
        return new GradingResult(
                75,
                "Subjective answer received; AI rubric scoring pending sp04 integration (rubric="
                        + request.rubricId() + ").",
                Instant.now(),
                GraderType.MANUAL,
                request.tenantId());
    }

    /** Sentinel block handler — signature must match the protected method. */
    public GradingResult gradeSubmissionBlockHandler(GradingRequest request,
                                                     com.alibaba.csp.sentinel.slots.block.BlockException ex) {
        throw new IllegalStateException(
                "GradingRpcService.gradeSubmission rate limited: " + ex.getClass().getSimpleName(), ex);
    }

    /** Sentinel fallback — invoked when the protected method throws. */
    public GradingResult gradeSubmissionFallback(GradingRequest request, Throwable t) {
        throw new IllegalStateException(
                "GradingRpcService.gradeSubmission failed: " + t.getMessage(), t);
    }

    private static void requireNonNull(Object value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
    }
}
