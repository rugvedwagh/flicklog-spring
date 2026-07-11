package com.flicklog.common.exception;

import com.flicklog.common.exception.ApiException;
import com.flicklog.common.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    @Test
    void handleApiException_devProfile_includesStackTrace() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("dev");
        GlobalExceptionHandler handler = new GlobalExceptionHandler(env);

        ApiException ex = new ApiException("Post not found", 404);

        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response = handler.handleApiException(ex);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody().getMessage()).isEqualTo("Post not found");
        assertThat(response.getBody().getStack()).isNotNull();
        assertThat(response.getBody().getStack().length).isGreaterThan(0);
    }

    // Regression test: prod used to leak stack traces to clients because the
    // old check read a NODE_ENV property that Spring never actually sets.
    @Test
    void handleApiException_prodProfile_omitsStackTrace() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        GlobalExceptionHandler handler = new GlobalExceptionHandler(env);

        ApiException ex = new ApiException("Post not found", 404);

        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response = handler.handleApiException(ex);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody().getMessage()).isEqualTo("Post not found");
        assertThat(response.getBody().getStack()).isNull();
    }

    @Test
    void handleApiException_usesExceptionStatusCode() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        GlobalExceptionHandler handler = new GlobalExceptionHandler(env);

        ApiException ex = new ApiException(403, "Forbidden action");

        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response = handler.handleApiException(ex);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void handleNotFound_returns404WithRouteNotFoundMessage() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler(new MockEnvironment());

        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response = handler.handleNotFound();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().getMessage()).isEqualTo("Route not found");
    }

    @Test
    void handleGeneric_devProfile_includesStackTrace() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("dev");
        GlobalExceptionHandler handler = new GlobalExceptionHandler(env);

        RuntimeException ex = new RuntimeException("Unexpected failure");

        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response = handler.handleGeneric(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().getMessage()).isEqualTo("Unexpected failure");
        assertThat(response.getBody().getStack()).isNotNull();
    }

    // UPDATED: prod must now return a generic message, not the raw exception message
    @Test
    void handleGeneric_prodProfile_omitsStackTrace() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        GlobalExceptionHandler handler = new GlobalExceptionHandler(env);

        RuntimeException ex = new RuntimeException("Unexpected failure");

        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response = handler.handleGeneric(ex);

        assertThat(response.getBody().getStack()).isNull();
        assertThat(response.getBody().getMessage()).isEqualTo("Internal Server Error");
    }

    // NEW: regression test for the message-leak fix specifically
    @Test
    void handleGeneric_prodProfile_neverLeaksRawExceptionMessage() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        GlobalExceptionHandler handler = new GlobalExceptionHandler(env);

        RuntimeException ex = new RuntimeException("Connection refused: connect flicklog-mongo:27017");

        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response = handler.handleGeneric(ex);

        assertThat(response.getBody().getMessage())
                .isEqualTo("Internal Server Error")
                .doesNotContain("flicklog-mongo");
    }

    @Test
    void handleGeneric_withNullMessage_defaultsToInternalServerError() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        GlobalExceptionHandler handler = new GlobalExceptionHandler(env);

        RuntimeException ex = new RuntimeException(); // no message

        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response = handler.handleGeneric(ex);

        assertThat(response.getBody().getMessage()).isEqualTo("Internal Server Error");
    }
}