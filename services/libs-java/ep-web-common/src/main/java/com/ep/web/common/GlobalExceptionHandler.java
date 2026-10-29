package com.ep.web.common;

import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.NoSuchElementException;

/**
 * Global exception handler. Translates unhandled exceptions into structured
 * {@link ErrorResponse} bodies with stable HTTP status codes. Stack traces are
 * never included in the response body; they are logged at WARN/ERROR level
 * server-side instead.
 *
 * <p>This is an infrastructure-level {@code @RestControllerAdvice}: when the
 * ep-web-common JAR is on the classpath of a Spring Boot service, Spring picks
 * it up automatically. Services that need to handle their own domain
 * exceptions (e.g. {@code InsufficientStockException} in core-business) may
 * declare their own {@code @RestControllerAdvice} with higher precedence; the
 * most specific {@code @ExceptionHandler} wins.</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static ErrorResponse body(String code, String message, HttpStatus status,
                                      jakarta.servlet.http.HttpServletRequest request) {
        String traceId = MDC.get("traceId");
        // v8 P2 (D-243): localization applies ONLY to fixed infrastructure
        // copy (INTERNAL_ERROR etc.). Business-detail messages (e.g. refund
        // overdraw, validation field paths) pass through verbatim — their
        // localization belongs to the owning service, and the machine code
        // stays ASCII for API clients.
        String localized = ErrorMessages.forCode(code, message, request);
        return new ErrorResponse(code, localized, traceId != null ? traceId : "N/A",
                System.currentTimeMillis());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(IllegalArgumentException ex,
            jakarta.servlet.http.HttpServletRequest request) {
        log.warn("Bad request: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(body("BAD_REQUEST", ex.getMessage(), HttpStatus.BAD_REQUEST, request));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleConflict(IllegalStateException ex,
            jakarta.servlet.http.HttpServletRequest request) {
        log.warn("Conflict: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(body("CONFLICT", ex.getMessage(), HttpStatus.CONFLICT, request));
    }

    @ExceptionHandler({EntityNotFoundException.class, NoSuchElementException.class})
    public ResponseEntity<ErrorResponse> handleNotFound(RuntimeException ex,
            jakarta.servlet.http.HttpServletRequest request) {
        log.warn("Not found: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(body("NOT_FOUND", ex.getMessage(), HttpStatus.NOT_FOUND, request));
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLock(OptimisticLockingFailureException ex,
            jakarta.servlet.http.HttpServletRequest request) {
        log.warn("Optimistic locking failure: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(body("CONFLICT", ex.getMessage(), HttpStatus.CONFLICT, request));
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ErrorResponse> handleForbidden(ForbiddenException ex,
            jakarta.servlet.http.HttpServletRequest request) {
        log.warn("Forbidden: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(body("FORBIDDEN", ex.getMessage(), HttpStatus.FORBIDDEN, request));
    }

    @ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(org.springframework.web.bind.MethodArgumentNotValidException ex,
            jakarta.servlet.http.HttpServletRequest request) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(java.util.stream.Collectors.joining("; "));
        log.warn("Validation failed: {}", detail);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(body("VALIDATION_ERROR", detail, HttpStatus.BAD_REQUEST, request));
    }

    @ExceptionHandler(jakarta.validation.ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(jakarta.validation.ConstraintViolationException ex,
            jakarta.servlet.http.HttpServletRequest request) {
        String detail = ex.getConstraintViolations().stream()
                .map(cv -> cv.getPropertyPath() + ": " + cv.getMessage())
                .collect(java.util.stream.Collectors.joining("; "));
        log.warn("Constraint violation: {}", detail);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(body("VALIDATION_ERROR", detail, HttpStatus.BAD_REQUEST, request));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleInternal(Exception ex,
            jakarta.servlet.http.HttpServletRequest request) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(body("INTERNAL_ERROR",
                        "An unexpected error occurred. Please contact support if the problem persists.",
                        HttpStatus.INTERNAL_SERVER_ERROR, request));
    }
}
