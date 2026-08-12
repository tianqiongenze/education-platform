package com.ep.grading.sandbox.judge0;

import java.io.Serializable;

/**
 * D-185: A task enqueued on the Judge0 execution queue.
 * <p>
 * The queue mirrors the Judge0 submission model: a self-contained unit of work
 * carrying the language, source, stdin, and timeout. The {@code token} is the
 * client-facing correlation id used to look up the result later.
 *
 * @param token      unique task id (used as the Redis result key suffix)
 * @param language   one of the {@link com.ep.grading.sandbox.LanguageSpec} names
 * @param sourceCode source to compile and run
 * @param stdin      standard input (may be empty)
 * @param timeoutSec execution timeout in seconds
 */
public record Judge0Task(
        String token,
        String language,
        String sourceCode,
        String stdin,
        int timeoutSec
) implements Serializable {
}
