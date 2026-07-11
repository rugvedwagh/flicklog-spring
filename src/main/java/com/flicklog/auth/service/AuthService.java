package com.flicklog.auth.service;

import com.flicklog.common.JwtProperties;
import com.flicklog.auth.dto.request.LoginRequest;
import com.flicklog.auth.dto.request.RegisterRequest;
import com.flicklog.common.exception.ApiException;
import com.flicklog.user.model.Session;
import com.flicklog.user.model.User;
import com.flicklog.user.repository.UserRepository;
import com.flicklog.auth.security.JwtTokenService;
import com.flicklog.auth.security.LoginRateLimiter;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Mirrors controllers/auth.controllers.js.
 */

@Slf4j
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;
    private final JwtProperties jwtProperties;
    private final LoginRateLimiter loginRateLimiter;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                       JwtTokenService jwtTokenService, JwtProperties jwtProperties, LoginRateLimiter loginRateLimiter) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenService = jwtTokenService;
        this.jwtProperties = jwtProperties;
        this.loginRateLimiter = loginRateLimiter;
    }

    public AuthResult login(LoginRequest request, String userAgent, String ipAddress) {
        String emailKey = "email:" + request.getEmail().toLowerCase();
        String ipKey = "ip:" + ipAddress;

        if (loginRateLimiter.isBlocked(emailKey) || loginRateLimiter.isBlocked(ipKey)) {
            log.warn("Login blocked: too many failed attempts for email={} or ip={}", request.getEmail(), ipAddress);
            throw new ApiException(429, "Too many failed login attempts. Please try again later.");
        }

        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> {
                    loginRateLimiter.recordFailure(emailKey);
                    loginRateLimiter.recordFailure(ipKey);
                    log.warn("Login failed: no user found for email {}", request.getEmail());
                    return new ApiException("User not found", 404);
                });

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            loginRateLimiter.recordFailure(emailKey);
            loginRateLimiter.recordFailure(ipKey);
            log.warn("Login failed: incorrect password for user {}", user.getId());
            throw new ApiException("Incorrect password", 400);
        }

        loginRateLimiter.reset(emailKey);
        loginRateLimiter.reset(ipKey);
        log.info("User {} logged in", user.getId());
        return issueSession(user, userAgent);
    }

    public AuthResult register(RegisterRequest request, String userAgent) {
        if (isBlank(request.getEmail()) || isBlank(request.getPassword())
                || isBlank(request.getConfirmPassword()) || isBlank(request.getFirstName()) || isBlank(request.getLastName())) {
            throw new ApiException("All fields are required", 400);
        }
        if (request.getPassword().length() < 4) {
            throw new ApiException("Password must be at least 4 characters long", 400);
        }
        if (userRepository.findByEmail(request.getEmail()).isPresent()) {
            log.warn("Registration failed: email {} already in use", request.getEmail());
            throw new ApiException("Email is already in use", 400);
        }
        if (!request.getPassword().equals(request.getConfirmPassword())) {
            throw new ApiException("Passwords don't match", 400);
        }

        User newUser = new User();
        newUser.setEmail(request.getEmail());
        newUser.setPassword(passwordEncoder.encode(request.getPassword()));
        newUser.setName(request.getFirstName() + " " + request.getLastName());

        // Save first so Mongo assigns an _id before we mint tokens that embed it
        // Save first so Mongo assigns an _id before we mint tokens that embed it
        newUser = userRepository.save(newUser);
        log.info("New user registered: {}", newUser.getId());

        return issueSession(newUser, userAgent);
    }

    private AuthResult issueSession(User user, String userAgent) {
        pruneExpiredSessions(user);

        String sessionId = UUID.randomUUID().toString();
        String csrfToken = jwtTokenService.generateCsrfToken();
        String refreshToken = jwtTokenService.generateRefreshToken(user);
        String accessToken = jwtTokenService.generateAccessToken(user);

        Session session = new Session(csrfToken, refreshToken, sessionId, userAgent, java.time.Instant.now());
        user.getSessions().add(session);
        userRepository.save(user);

        return new AuthResult(user, accessToken, refreshToken, csrfToken, sessionId);
    }

    public void logout(String sessionId) {
        if (isBlank(sessionId)) {
            throw new ApiException("Session ID is required", 400);
        }

        User user = userRepository.findBySessions_SessionId(sessionId)
                .orElseThrow(() -> {
                    log.warn("Logout failed: no user found for session {}", sessionId);
                    return new ApiException("User not found", 404);
                });

        user.setSessions(new java.util.ArrayList<>(user.getSessions().stream()
                .filter(s -> !s.getSessionId().equals(sessionId))
                .toList()));

        userRepository.save(user);
        log.info("User {} logged out (session {})", user.getId(), sessionId);
    }

    public RefreshResult refreshAccessToken(String refreshToken, String sessionId) {
        if (isBlank(refreshToken) || isBlank(sessionId)) {
            throw new ApiException(401, "Refresh token is missing or invalid");
        }

        Claims claims;
        try {
            claims = jwtTokenService.verifyRefreshToken(refreshToken);
        } catch (JwtException e) {
            log.warn("Refresh token rejected: {}", e.getMessage());
            throw new ApiException(401, "Invalid or expired refresh token");
        }

        User user = userRepository.findById(claims.get("id", String.class))
                .orElseThrow(() -> new ApiException(404, "User not found"));

        pruneExpiredSessions(user);

        Session session = user.getSessions().stream()
                .filter(s -> s.getSessionId().equals(sessionId))
                .findFirst()
                .orElse(null);

        if (session == null) {
            log.warn("Refresh rejected: no session {} for user {}", sessionId, user.getId());
            throw new ApiException(403, "Session not found or refresh token is invalid");
        }

        if (!constantTimeEquals(session.getRefreshToken(), refreshToken)) {
            // The token's signature/expiry are valid, but it doesn't match what's
            // currently stored for this session - it was already rotated out.
            // Treat this as likely theft/replay: nuke every session for this user.
            log.warn("Refresh token reuse detected for user {} (session {}) - revoking all sessions", user.getId(), sessionId);
            user.setSessions(new java.util.ArrayList<>());
            userRepository.save(user);
            throw new ApiException(403, "Session invalidated due to suspected token reuse. Please log in again.");
        }

        String newRefreshToken = jwtTokenService.generateRefreshToken(user);
        String newAccessToken = jwtTokenService.generateAccessToken(user);
        session.setRefreshToken(newRefreshToken);
        userRepository.save(user);

        log.info("Access token refreshed for user {} (session {})", user.getId(), sessionId);
        return new RefreshResult(newAccessToken, newRefreshToken);
    }

    // Mirrors middleware/csrf.middleware.js's verifyCsrfToken, run before refreshAccessToken
    // on POST /auth/refresh-token/secure.
    public void verifyCsrf(String refreshToken, String sessionId, String csrfToken) {
        if (isBlank(csrfToken) || isBlank(refreshToken) || isBlank(sessionId)) {
            throw new ApiException("Missing CSRF, refresh token, or session ID", 403);
        }

        Claims decoded;
        try {
            decoded = jwtTokenService.verifyRefreshToken(refreshToken);
        } catch (JwtException e) {
            log.warn("CSRF check failed: refresh token rejected ({})", e.getMessage());
            throw new ApiException("Invalid or expired refresh token", 403);
        }

        User user = userRepository.findById(decoded.get("id", String.class))
                .orElseThrow(() -> new ApiException("User not found", 404));

        boolean valid = user.getSessions().stream()
                .anyMatch(s -> s.getSessionId().equals(sessionId)
                        && constantTimeEquals(s.getRefreshToken(), refreshToken)
                        && constantTimeEquals(s.getCsrfToken(), csrfToken));

        if (!valid) {
            log.warn("CSRF check failed: session/CSRF mismatch for session {}", sessionId);
            throw new ApiException("Invalid session or CSRF token", 403);
        }
    }

    // A session's own JWT refresh token can't outlive refreshExpiryDays, so any
    // session record older than that is dead weight - safe to drop it here.
    private void pruneExpiredSessions(User user) {
        Instant cutoff = Instant.now().minus(Duration.ofDays(jwtProperties.getRefreshExpiryDays()));
        List<Session> active = user.getSessions().stream()
                .filter(s -> s.getCreatedAt().isAfter(cutoff))
                .toList();

        int removed = user.getSessions().size() - active.size();
        if (removed > 0) {
            log.info("Pruned {} stale session(s) for user {}", removed, user.getId());
        }
        user.setSessions(new ArrayList<>(active));
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    // Plain String.equals() short-circuits on the first mismatched character,
    // which leaks timing information about secrets. MessageDigest.isEqual()
    // always compares every byte, regardless of where the first difference is.
    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return java.security.MessageDigest.isEqual(
                a.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                b.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
