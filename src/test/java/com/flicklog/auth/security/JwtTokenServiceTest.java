// src/test/java/com/flicklog/security/JwtTokenServiceTest.java
package com.flicklog.auth.security;

import com.flicklog.common.JwtProperties;
import com.flicklog.user.model.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenServiceTest {

    private JwtTokenService jwtTokenService;
    private User user;

    @BeforeEach
    void setUp() {
        JwtProperties props = new JwtProperties();
        props.setAccessSecret("this-is-a-test-secret-that-is-long-enough-256-bits");
        props.setRefreshSecret("a-different-test-secret-also-long-enough-256-bits");
        props.setAccessExpiryMinutes(15);
        props.setRefreshExpiryDays(7);

        jwtTokenService = new JwtTokenService(props);

        user = new User();
        user.setId("507f1f77bcf86cd799439011");
        user.setEmail("test@example.com");
    }

    @Test
    void generateAndVerifyAccessToken_roundTrips() {
        String token = jwtTokenService.generateAccessToken(user);
        Claims claims = jwtTokenService.verifyAccessToken(token);

        assertThat(claims.get("id", String.class)).isEqualTo(user.getId());
        assertThat(claims.get("email", String.class)).isEqualTo(user.getEmail());
    }

    @Test
    void verifyAccessToken_withRefreshTokenSignature_fails() {
        String refreshToken = jwtTokenService.generateRefreshToken(user);

        // access token verification must reject a token signed with the refresh secret
        assertThatThrownBy(() -> jwtTokenService.verifyAccessToken(refreshToken))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void generateCsrfToken_producesDifferentValuesEachTime() {
        String token1 = jwtTokenService.generateCsrfToken();
        String token2 = jwtTokenService.generateCsrfToken();

        assertThat(token1).isNotEqualTo(token2);
        assertThat(token1).hasSize(64); // 32 bytes -> 64 hex chars
    }
}