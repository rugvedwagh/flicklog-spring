package com.flicklog.common.exception;

import lombok.Getter;

/**
 * Mirrors utils/create-error.js's createHttpError(msg, code) pattern -
 * a single exception type carrying an HTTP status, thrown from anywhere
 * in a controller/service and turned into a JSON response by GlobalExceptionHandler.
 */
@Getter
public class ApiException extends RuntimeException {

    private final int statusCode;

    public ApiException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    // Convenience overload matching call sites like createHttpError(401, 'message')
    public ApiException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }
}
