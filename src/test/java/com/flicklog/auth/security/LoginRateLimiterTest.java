package com.flicklog.auth.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoginRateLimiterTest {

    @Test
    void isBlocked_keepsFailureCountsUntilTheFifthFailureLocksTheKey() {
        LoginRateLimiter limiter = new LoginRateLimiter();
        String key = "email:test@example.com";

        for (int i = 0; i < 5; i++) {
            assertThat(limiter.isBlocked(key)).isFalse();
            limiter.recordFailure(key);
        }

        assertThat(limiter.isBlocked(key)).isTrue();
    }
}
