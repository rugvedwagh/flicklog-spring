package com.flicklog.auth.controller;

import com.flicklog.common.config.AppProperties;
import com.flicklog.auth.dto.request.LoginRequest;
import com.flicklog.auth.dto.request.RegisterRequest;
import com.flicklog.auth.dto.response.AuthResponse;
import com.flicklog.auth.service.AuthResult;
import com.flicklog.auth.service.AuthService;
import com.flicklog.auth.service.RefreshResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Mirrors routes/auth.routes.js + controllers/auth.controllers.js.
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;
    private final AppProperties appProperties;

    public AuthController(AuthService authService, AppProperties appProperties) {
        this.authService = authService;
        this.appProperties = appProperties;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request, HttpServletRequest httpRequest,
                                                 HttpServletResponse httpResponse) {
        AuthResult result = authService.register(request, httpRequest.getHeader("User-Agent"));
        setRefreshTokenCookie(httpResponse, result.getRefreshToken());

        return ResponseEntity.status(201).body(new AuthResponse(
                result.getUser(), result.getAccessToken(), result.getCsrfToken(), result.getSessionId()));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest,
                                              HttpServletResponse httpResponse) {
        AuthResult result = authService.login(request, httpRequest.getHeader("User-Agent"));
        setRefreshTokenCookie(httpResponse, result.getRefreshToken());

        return ResponseEntity.ok(new AuthResponse(
                result.getUser(), result.getAccessToken(), result.getCsrfToken(), result.getSessionId()));
    }

    @PostMapping("/refresh-token/secure")
    public ResponseEntity<Map<String, String>> refreshToken(
            @CookieValue(value = "refreshToken", required = false) String refreshToken,
            @RequestHeader(value = "x-session-id", required = false) String sessionId,
            @RequestHeader(value = "x-xsrf-token", required = false) String csrfToken,
            HttpServletResponse httpResponse) {

        authService.verifyCsrf(refreshToken, sessionId, csrfToken);
        RefreshResult result = authService.refreshAccessToken(refreshToken, sessionId);
        setRefreshTokenCookie(httpResponse, result.getRefreshToken());

        return ResponseEntity.ok(Map.of("accessToken", result.getAccessToken()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout(
            @CookieValue(value = "refreshToken", required = false) String refreshToken,
            @RequestHeader(value = "x-session-id", required = false) String sessionId,
            @RequestHeader(value = "x-xsrf-token", required = false) String csrfToken,
            HttpServletResponse response) {

        authService.verifyCsrf(refreshToken, sessionId, csrfToken);
        authService.logout(sessionId);
        clearRefreshTokenCookie(response);

        return ResponseEntity.ok(Map.of("message", "Logged out successfully"));
    }

    private void setRefreshTokenCookie(HttpServletResponse response, String refreshToken) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from("refreshToken", refreshToken)
                .httpOnly(true)
                .secure(appProperties.getCookie().isSecure())
                .sameSite(appProperties.getCookie().getSameSite())
                .path("/")
                .maxAge(7L * 24 * 60 * 60);

        String cookieDomain = appProperties.getCookie().getDomain();
        if (cookieDomain != null && !cookieDomain.isBlank()) {
            builder.domain(cookieDomain);
        }
        response.addHeader("Set-Cookie", builder.build().toString());
    }

    private void clearRefreshTokenCookie(HttpServletResponse response) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from("refreshToken", "")
                .httpOnly(true)
                .secure(appProperties.getCookie().isSecure())
                .sameSite(appProperties.getCookie().getSameSite())
                .path("/")
                .maxAge(0);
        String cookieDomain = appProperties.getCookie().getDomain();
        if (cookieDomain != null && !cookieDomain.isBlank()) {
            builder.domain(cookieDomain);
        }
        response.addHeader("Set-Cookie", builder.build().toString());
    }
}
