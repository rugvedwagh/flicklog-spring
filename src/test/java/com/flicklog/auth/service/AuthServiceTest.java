// src/test/java/com/flicklog/service/AuthServiceTest.java
package com.flicklog.auth.service;

import com.flicklog.common.JwtProperties;
import com.flicklog.auth.dto.request.LoginRequest;
import com.flicklog.auth.dto.request.RegisterRequest;
import com.flicklog.common.exception.ApiException;
import com.flicklog.user.model.User;
import com.flicklog.user.repository.UserRepository;
import com.flicklog.auth.security.JwtTokenService;
import com.flicklog.auth.security.LoginRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import com.flicklog.user.model.Session;
import io.jsonwebtoken.Claims;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtTokenService jwtTokenService;
    @Mock private JwtProperties jwtProperties;

    private AuthService authService;

    private final LoginRateLimiter loginRateLimiter = new LoginRateLimiter();

    private User existingUser;

    @BeforeEach
    void setUp() {
        existingUser = new User();
        existingUser.setId("507f1f77bcf86cd799439011");
        existingUser.setEmail("test@example.com");
        existingUser.setPassword("hashed-password");

        authService = new AuthService(userRepository, passwordEncoder, jwtTokenService, jwtProperties, loginRateLimiter);
    }

    @Test
    void login_withCorrectCredentials_returnsAuthResult() {
        LoginRequest request = new LoginRequest();
        request.setEmail("test@example.com");
        request.setPassword("password123");

        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(existingUser));
        when(passwordEncoder.matches("password123", "hashed-password")).thenReturn(true);
        when(jwtTokenService.generateCsrfToken()).thenReturn("csrf-token");
        when(jwtTokenService.generateRefreshToken(any())).thenReturn("refresh-token");
        when(jwtTokenService.generateAccessToken(any())).thenReturn("access-token");
        when(userRepository.save(any())).thenReturn(existingUser);
        when(jwtProperties.getRefreshExpiryDays()).thenReturn(7L);

        AuthResult result = authService.login(request, "test-agent", "127.0.0.1");

        assertThat(result.getAccessToken()).isEqualTo("access-token");
        assertThat(result.getCsrfToken()).isEqualTo("csrf-token");
        verify(userRepository).save(existingUser);
    }

    @Test
    void login_withWrongPassword_throws400() {
        LoginRequest request = new LoginRequest();
        request.setEmail("test@example.com");
        request.setPassword("wrongpass");

        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(existingUser));
        when(passwordEncoder.matches("wrongpass", "hashed-password")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(request, "test-agent", "127.0.0.1"))
                .isInstanceOf(ApiException.class)
                .hasMessage("Incorrect password");
    }

    @Test
    void login_withUnknownEmail_throws404() {
        LoginRequest request = new LoginRequest();
        request.setEmail("ghost@example.com");
        request.setPassword("password123");

        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(request, "test-agent", "127.0.0.1"))
                .isInstanceOf(ApiException.class)
                .hasMessage("User not found");
    }

    @Test
    void register_withMismatchedPasswords_throws400() {
        RegisterRequest request = new RegisterRequest();
        request.setEmail("new@example.com");
        request.setPassword("password123");
        request.setConfirmPassword("different");
        request.setFirstName("Jane");
        request.setLastName("Doe");

        when(userRepository.findByEmail("new@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.register(request, "test-agent"))
                .isInstanceOf(ApiException.class)
                .hasMessage("Passwords don't match");
    }

    @Test
    void register_withExistingEmail_throws400() {
        RegisterRequest request = new RegisterRequest();
        request.setEmail("test@example.com");
        request.setPassword("password123");
        request.setConfirmPassword("password123");
        request.setFirstName("Jane");
        request.setLastName("Doe");

        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(existingUser));

        assertThatThrownBy(() -> authService.register(request, "test-agent"))
                .isInstanceOf(ApiException.class)
                .hasMessage("Email is already in use");
    }

    // --- refreshAccessToken ---

    @Test
    void refreshAccessToken_withValidSessionAndMatchingToken_rotatesAndReturnsNewTokens() {
        Session session = new Session("csrf-tok", "current-refresh-token", "session-1", "some-agent", java.time.Instant.now());
        existingUser.getSessions().add(session);

        Claims claims = mock(Claims.class);
        when(claims.get("id", String.class)).thenReturn(existingUser.getId());
        when(jwtTokenService.verifyRefreshToken("current-refresh-token")).thenReturn(claims);
        when(userRepository.findById(existingUser.getId())).thenReturn(Optional.of(existingUser));
        when(jwtTokenService.generateRefreshToken(existingUser)).thenReturn("rotated-refresh-token");
        when(jwtTokenService.generateAccessToken(existingUser)).thenReturn("new-access-token");
        when(jwtProperties.getRefreshExpiryDays()).thenReturn(7L);

        RefreshResult result = authService.refreshAccessToken("current-refresh-token", "session-1");

        assertThat(result.getAccessToken()).isEqualTo("new-access-token");
        assertThat(result.getRefreshToken()).isEqualTo("rotated-refresh-token");
        assertThat(session.getRefreshToken()).isEqualTo("rotated-refresh-token"); // stored session was updated
        verify(userRepository).save(existingUser);
    }

    @Test
    void refreshAccessToken_withUnknownSessionId_throws403() {
        Claims claims = mock(Claims.class);
        when(claims.get("id", String.class)).thenReturn(existingUser.getId());
        when(jwtTokenService.verifyRefreshToken("some-token")).thenReturn(claims);
        when(userRepository.findById(existingUser.getId())).thenReturn(Optional.of(existingUser));
        when(jwtProperties.getRefreshExpiryDays()).thenReturn(7L);

        assertThatThrownBy(() -> authService.refreshAccessToken("some-token", "no-such-session"))
                .isInstanceOf(ApiException.class)
                .hasMessage("Session not found or refresh token is invalid");
    }

    // Regression test for the reuse-detection feature itself: presenting a
    // stale (already-rotated) refresh token should wipe every session, not
    // just reject the one request.
    @Test
    void refreshAccessToken_withStaleReusedToken_revokesAllSessionsAndThrows403() {
        Session session = new Session("csrf-tok", "current-refresh-token", "session-1", "some-agent", java.time.Instant.now());
        Session otherDeviceSession = new Session("csrf-tok-2", "other-device-token", "session-2", "other-agent", java.time.Instant.now());
        existingUser.getSessions().add(session);
        existingUser.getSessions().add(otherDeviceSession);

        Claims claims = mock(Claims.class);
        when(claims.get("id", String.class)).thenReturn(existingUser.getId());
        when(jwtTokenService.verifyRefreshToken("stale-token")).thenReturn(claims);
        when(userRepository.findById(existingUser.getId())).thenReturn(Optional.of(existingUser));
        when(jwtProperties.getRefreshExpiryDays()).thenReturn(7L);

        assertThatThrownBy(() -> authService.refreshAccessToken("stale-token", "session-1"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("suspected token reuse");

        assertThat(existingUser.getSessions()).isEmpty(); // both sessions wiped, not just session-1
    }

    @Test
    void refreshAccessToken_withInvalidOrExpiredJwt_throws401() {
        when(jwtTokenService.verifyRefreshToken("bad-token")).thenThrow(new io.jsonwebtoken.JwtException("expired"));

        assertThatThrownBy(() -> authService.refreshAccessToken("bad-token", "session-1"))
                .isInstanceOf(ApiException.class)
                .hasMessage("Invalid or expired refresh token");
    }

    @Test
    void refreshAccessToken_withBlankSessionId_throws401() {
        assertThatThrownBy(() -> authService.refreshAccessToken("some-token", ""))
                .isInstanceOf(ApiException.class)
                .hasMessage("Refresh token is missing or invalid");

        verifyNoInteractions(jwtTokenService);
    }

    @Test
    void login_prunesExpiredSessionsButKeepsFreshOnes() {
        Session staleSession = new Session("old-csrf", "old-token", "old-session",
                "old-agent", java.time.Instant.now().minus(java.time.Duration.ofDays(10)));
        Session freshSession = new Session("fresh-csrf", "fresh-token", "fresh-session",
                "fresh-agent", java.time.Instant.now().minus(java.time.Duration.ofDays(1)));
        existingUser.getSessions().add(staleSession);
        existingUser.getSessions().add(freshSession);
        existingUser.setPassword("hashed-password");

        LoginRequest request = new LoginRequest();
        request.setEmail("test@example.com");
        request.setPassword("correct-password");

        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(existingUser));
        when(passwordEncoder.matches("correct-password", "hashed-password")).thenReturn(true);
        when(jwtTokenService.generateCsrfToken()).thenReturn("new-csrf");
        when(jwtTokenService.generateRefreshToken(existingUser)).thenReturn("new-refresh");
        when(jwtTokenService.generateAccessToken(existingUser)).thenReturn("new-access");
        when(jwtProperties.getRefreshExpiryDays()).thenReturn(7L);

        authService.login(request, "some-agent", "127.0.0.1");

        assertThat(existingUser.getSessions())
                .extracting(Session::getSessionId)
                .contains("fresh-session")
                .doesNotContain("old-session");
        assertThat(existingUser.getSessions()).hasSize(2); // fresh-session survives + one new session from this login
    }

    @Test
    void login_withTooManyFailedEmailAttempts_throws429() {
        for (int i = 0; i < 5; i++) {
            loginRateLimiter.recordFailure("email:test@example.com");
        }

        LoginRequest request = new LoginRequest();
        request.setEmail("test@example.com");
        request.setPassword("password123");

        assertThatThrownBy(() -> authService.login(request, "test-agent", "127.0.0.1"))
                .isInstanceOf(ApiException.class)
                .hasMessage("Too many failed login attempts. Please try again later.");

        verifyNoInteractions(userRepository, passwordEncoder);
    }

    // Regression test for the gap we just closed: spraying low-volume guesses
// across many different emails from one IP should now trip the IP-based
// limiter, even though no single email individually hit its own threshold.
    @Test
    void login_withTooManyFailedAttemptsFromSameIp_acrossDifferentEmails_throws429() {
        for (int i = 0; i < 5; i++) {
            loginRateLimiter.recordFailure("ip:203.0.113.7");
        }

        LoginRequest request = new LoginRequest();
        request.setEmail("someone-not-tried-before@example.com");
        request.setPassword("password123");

        assertThatThrownBy(() -> authService.login(request, "test-agent", "203.0.113.7"))
                .isInstanceOf(ApiException.class)
                .hasMessage("Too many failed login attempts. Please try again later.");

        verifyNoInteractions(userRepository, passwordEncoder);
    }
}