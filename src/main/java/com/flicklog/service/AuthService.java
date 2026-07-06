package com.flicklog.service;

import com.flicklog.dto.request.LoginRequest;
import com.flicklog.dto.request.RegisterRequest;
import com.flicklog.exception.ApiException;
import com.flicklog.model.Session;
import com.flicklog.model.User;
import com.flicklog.repository.UserRepository;
import com.flicklog.security.JwtTokenService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Mirrors controllers/auth.controllers.js.
 */
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtTokenService jwtTokenService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenService = jwtTokenService;
    }

    public AuthResult login(LoginRequest request, String userAgent) {
        if (isBlank(request.getEmail()) || isBlank(request.getPassword())) {
            throw new ApiException("Email and password are required", 400);
        }
        if (request.getPassword().length() < 4) {
            throw new ApiException("Password must be at least 4 characters long", 400);
        }

        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new ApiException("User not found", 404));

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new ApiException("Incorrect password", 400);
        }

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
        newUser = userRepository.save(newUser);

        return issueSession(newUser, userAgent);
    }

    private AuthResult issueSession(User user, String userAgent) {
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
                .orElseThrow(() -> new ApiException("User not found", 404));

        user.setSessions(new java.util.ArrayList<>(user.getSessions().stream()
                .filter(s -> !s.getSessionId().equals(sessionId))
                .toList()));

        userRepository.save(user);
    }

    public String refreshAccessToken(String refreshToken) {
        if (isBlank(refreshToken)) {
            throw new ApiException(401, "Refresh token is missing or invalid");
        }

        Claims claims;
        try {
            claims = jwtTokenService.verifyRefreshToken(refreshToken);
        } catch (JwtException e) {
            throw new ApiException(401, "Invalid or expired refresh token");
        }

        User user = userRepository.findById(claims.get("id", String.class))
                .orElseThrow(() -> new ApiException(404, "User not found"));

        boolean sessionExists = user.getSessions().stream()
                .anyMatch(s -> s.getRefreshToken().equals(refreshToken));

        if (!sessionExists) {
            throw new ApiException(403, "Session not found or refresh token is invalid");
        }

        return jwtTokenService.generateAccessToken(user);
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
            throw new ApiException("Invalid or expired refresh token", 403);
        }

        User user = userRepository.findById(decoded.get("id", String.class))
                .orElseThrow(() -> new ApiException("User not found", 404));

        boolean valid = user.getSessions().stream()
                .anyMatch(s -> s.getSessionId().equals(sessionId)
                        && s.getRefreshToken().equals(refreshToken)
                        && s.getCsrfToken().equals(csrfToken));

        if (!valid) {
            throw new ApiException("Invalid session or CSRF token", 403);
        }
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
