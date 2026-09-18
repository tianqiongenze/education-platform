package com.ep.grading.homework.impl;

import com.ep.grading.common.PlagiarismCheckFacade;
import com.ep.grading.common.PlagiarismCheckResult;
import com.ep.grading.common.SnowflakeIdGenerator;
import com.ep.grading.homework.PlagiarismCheckRepository;
import com.ep.grading.homework.entity.PlagiarismCheck;
import com.ep.grading.homework.entity.PlagiarismStatus;
import com.ep.grading.homework.entity.Submission;
import com.ep.grading.homework.service.PlagiarismService;
import com.ep.grading.homework.service.SubmissionService;
import com.alibaba.csp.sentinel.annotation.SentinelResource;
import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * D-048 P0-4: ConcurrentHashMap store replaced by Spring Data JPA
 * {@link PlagiarismCheckRepository}.
 * D-048 P0-7: {@link SentinelResource} on {@link #check} for rate limiting.
 * D-136: also implements the shared {@link PlagiarismCheckFacade} so the
 * {@code grading} bounded context can consume plagiarism outcomes without
 * importing {@code homework} entity classes (context isolation).
 */
@DubboService
public class PlagiarismServiceImpl implements PlagiarismService, PlagiarismCheckFacade {

    private final PlagiarismCheckRepository plagiarismCheckRepository;

    @DubboReference
    private SubmissionService submissionService;

    public PlagiarismServiceImpl(PlagiarismCheckRepository plagiarismCheckRepository) {
        this.plagiarismCheckRepository = plagiarismCheckRepository;
    }

    @Override
    @SentinelResource(value = "PlagiarismService.check",
            blockHandler = "checkBlockHandler", fallback = "checkFallback")
    @Transactional
    public PlagiarismCheck check(Long submissionId) {
        if (submissionId == null) {
            throw new IllegalArgumentException("submissionId cannot be null");
        }

        int contentLength = 0;
        if (submissionService != null) {
            Submission submission = submissionService.findById(submissionId);
            if (submission != null && submission.getContent() != null) {
                contentLength = submission.getContent().length();
            }
        }
        if (contentLength == 0) {
            contentLength = (int) (Math.abs(submissionId) * 17);
        }

        double similarityScore = (contentLength % 100) / 100.0;

        // D-224: the aggregate owns the similarity→verdict classification.
        PlagiarismCheck check = new PlagiarismCheck();
        check.setId(SnowflakeIdGenerator.getInstance().nextId());
        check.setSubmissionId(submissionId);
        check.classify(similarityScore);
        check.setMatchedSources("[]");
        return plagiarismCheckRepository.save(check);
    }

    @Override
    @Transactional(readOnly = true)
    public PlagiarismCheck findBySubmissionId(Long submissionId) {
        if (submissionId == null) {
            return null;
        }
        return plagiarismCheckRepository.findBySubmissionId(submissionId).orElse(null);
    }

    public PlagiarismCheck checkBlockHandler(Long submissionId,
                                             com.alibaba.csp.sentinel.slots.block.BlockException ex) {
        throw new IllegalStateException("Plagiarism check rate limited: " + ex.getClass().getSimpleName(), ex);
    }

    public PlagiarismCheck checkFallback(Long submissionId, Throwable t) {
        throw new IllegalStateException("Plagiarism check failed: " + t.getMessage(), t);
    }

    // ------------------------------------------------------------------
    // PlagiarismCheckFacade — context-neutral view for the grading context
    // ------------------------------------------------------------------

    @Override
    public PlagiarismCheckResult checkResult(Long submissionId) {
        PlagiarismCheck check = check(submissionId);
        if (check == null) {
            return null;
        }
        return new PlagiarismCheckResult(check.getSimilarityScore(), mapStatus(check.getStatus()));
    }

    private static PlagiarismCheckResult.Status mapStatus(PlagiarismStatus status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case PENDING -> PlagiarismCheckResult.Status.PENDING;
            case CLEAN -> PlagiarismCheckResult.Status.CLEAN;
            case FLAGGED -> PlagiarismCheckResult.Status.FLAGGED;
            case REVIEWING -> PlagiarismCheckResult.Status.REVIEWING;
        };
    }
}
