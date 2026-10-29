package com.ep.bff.config;

import com.ep.web.common.ForbiddenException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * D-242: maps the ep-web-common {@link ForbiddenException} (RoleChecker gates
 * on the Dubbo aggregation controllers) to 403. Without this advice the
 * standalone Jetty error path surfaced a 500 for a clean authorization
 * rejection — wrong semantics for an RBAC denial and noisy in ops.
 */
@RestControllerAdvice
public class ForbiddenExceptionAdvice {

    private static final Logger log = LoggerFactory.getLogger(ForbiddenExceptionAdvice.class);

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<String> handleForbidden(ForbiddenException ex) {
        log.warn("D-242: forbidden — {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body("{\"code\":\"FORBIDDEN\",\"message\":\"" + ex.getMessage() + "\"}");
    }
}
