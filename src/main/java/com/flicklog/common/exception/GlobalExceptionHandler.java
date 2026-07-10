package com.flicklog.common.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Mirrors middleware/error.middleware.js (ApiException -> {message, stack?})
 * and middleware/not-found.middleware.js ("Route not found").
 *
 * To get the not-found handler to fire (Spring doesn't do this by default),
 * application.yml needs:
 *   spring.mvc.throw-exception-if-no-handler-found: true
 *   spring.web.resources.add-mappings: false
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private final Environment env;

    public GlobalExceptionHandler(Environment env) {
        this.env = env;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    static class ErrorResponse {
        private String message;
        private String[] stack;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApiException(ApiException ex) {
        log.warn("Handled API exception ({}): {}", ex.getStatusCode(), ex.getMessage());

        ErrorResponse body = new ErrorResponse();
        body.setMessage(ex.getMessage());

        boolean isProduction = env.acceptsProfiles(org.springframework.core.env.Profiles.of("prod"));
        if (!isProduction) {
            body.setStack(Arrays.stream(ex.getStackTrace())
                    .map(StackTraceElement::toString)
                    .toArray(String[]::new));
        }

        return ResponseEntity.status(ex.getStatusCode()).body(body);
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<ErrorResponse> handleValidation(BindException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));

        log.warn("Validation failed: {}", message);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(message, null));
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("Route not found", null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex) {
        log.error("Unhandled exception reached the global handler", ex);

        ErrorResponse body = new ErrorResponse();
        body.setMessage(ex.getMessage() != null ? ex.getMessage() : "Internal Server Error");

        boolean isProduction = env.acceptsProfiles(org.springframework.core.env.Profiles.of("prod"));
        if (!isProduction) {
            body.setStack(Arrays.stream(ex.getStackTrace())
                    .map(StackTraceElement::toString)
                    .toArray(String[]::new));
        }

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
