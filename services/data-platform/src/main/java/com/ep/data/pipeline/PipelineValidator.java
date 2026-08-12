package com.ep.data.pipeline;

import java.util.regex.Pattern;

/**
 * Validates {@link CdcPipelineConfig} before a Flink CDC pipeline is submitted.
 *
 * <p>Enforces platform guardrails:
 * <ul>
 *   <li>C-03: commitMode should be {@code checkpoint}; {@code transactional} is allowed
 *       but logs a recommendation to switch to checkpoint.</li>
 *   <li>D-028: Pulsar topic must live under {@code persistent://public/cdc/}.</li>
 * </ul>
 */
public final class PipelineValidator {

    /** CDC topics must be under persistent://public/cdc/. */
    public static final Pattern TOPIC_PATTERN = Pattern.compile("persistent://public/cdc/.+");

    private PipelineValidator() {
    }

    /**
     * Validate the given config.
     *
     * @param config the config to validate
     * @throws IllegalArgumentException if any rule is violated
     */
    public static void validate(CdcPipelineConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }

        // sourceTable non-blank
        if (config.getSourceTable() == null || config.getSourceTable().isBlank()) {
            throw new IllegalArgumentException("sourceTable must not be blank");
        }

        // pulsarTopic must match the CDC namespace pattern
        String topic = config.getPulsarTopic();
        if (topic == null || !TOPIC_PATTERN.matcher(topic).matches()) {
            throw new IllegalArgumentException(
                    "pulsarTopic must match 'persistent://public/cdc/.*', was: " + topic);
        }

        // parallelism 1-256
        int parallelism = config.getParallelism();
        if (parallelism < 1 || parallelism > 256) {
            throw new IllegalArgumentException(
                    "parallelism must be between 1 and 256, was: " + parallelism);
        }

        // checkpointIntervalMs 1000-3600000
        long ck = config.getCheckpointIntervalMs();
        if (ck < 1000L || ck > 3_600_000L) {
            throw new IllegalArgumentException(
                    "checkpointIntervalMs must be between 1000 and 3600000, was: " + ck);
        }

        // targetFileSizeMb 1-1024
        int fsize = config.getTargetFileSizeMb();
        if (fsize < 1 || fsize > 1024) {
            throw new IllegalArgumentException(
                    "targetFileSizeMb must be between 1 and 1024, was: " + fsize);
        }

        // commitMode must be checkpoint or transactional
        String commitMode = config.getCommitMode();
        if (commitMode == null
                || (!"checkpoint".equals(commitMode) && !"transactional".equals(commitMode))) {
            throw new IllegalArgumentException(
                    "commitMode must be 'checkpoint' or 'transactional', was: " + commitMode);
        }

        // C-03: warn (do not fail) if transactional; recommend checkpoint
        if ("transactional".equals(commitMode)) {
            // Log to stderr as a lightweight recommendation channel; tests assert no exception.
            System.err.println("[PipelineValidator] C-03 warning: commitMode='transactional' "
                    + "is not recommended; prefer 'checkpoint' for Flink→Iceberg. "
                    + "pipeline=" + config.getSourceTable());
        }

        // icebergTable non-blank
        if (config.getIcebergTable() == null || config.getIcebergTable().isBlank()) {
            throw new IllegalArgumentException("icebergTable must not be blank");
        }

        // icebergDatabase non-blank
        if (config.getIcebergDatabase() == null || config.getIcebergDatabase().isBlank()) {
            throw new IllegalArgumentException("icebergDatabase must not be blank");
        }
    }
}
