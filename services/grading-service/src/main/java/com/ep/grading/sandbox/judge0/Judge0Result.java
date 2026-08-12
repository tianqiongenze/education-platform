package com.ep.grading.sandbox.judge0;

import com.ep.grading.sandbox.ExecutionResult;

import java.io.Serializable;

/**
 * D-185: Result wrapper stored under {@code judge0:result:{token}} in Redis.
 * Combines the correlation token with the sandbox {@link ExecutionResult}.
 *
 * @param token  the task token this result corresponds to
 * @param result the execution result (never null)
 */
public record Judge0Result(
        String token,
        ExecutionResult result
) implements Serializable {
}
