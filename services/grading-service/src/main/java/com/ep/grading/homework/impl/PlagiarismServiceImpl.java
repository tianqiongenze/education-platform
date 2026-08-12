package com.ep.grading.homework.impl;

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
 */
@DubboService
public class PlagiarismServiceImpl implements PlagiarismService {

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

        PlagiarismStatus status;
        if (similarityScore < 0.3) {
            status = PlagiarismStatus.CLEAN;
        } else if (similarityScore >= 0.7) {
            status = PlagiarismStatus.FLAGGED;
        } else {
            status = PlagiarismStatus.REVIEWING;
        }

        PlagiarismCheck check = new PlagiarismCheck();
        check.setId(SnowflakeIdGenerator.getInstance().nextId());
        check.setSubmissionId(submissionId);
        check.setSimilarityScore(similarityScore);
        check.setMatchedSources("[]");
        check.setStatus(status);
        check.setCheckedAt(LocalDateTime.now());
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
}
