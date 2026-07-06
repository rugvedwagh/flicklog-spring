package com.flicklog.controller;

import com.flicklog.dto.request.LoginRequest;
import com.flicklog.dto.request.RegisterRequest;
import com.flicklog.dto.response.AuthResponse;
import com.flicklog.exception.ApiException;
import com.flicklog.service.AuthResult;
import com.flicklog.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
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

    @Value("${app.cookie.domain:}")
    private String cookieDomain;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@RequestBody RegisterRequest request, HttpServletRequest httpRequest,
                                                  HttpServletResponse httpResponse) {
        AuthResult result = authService.register(request, httpRequest.getHeader("User-Agent"));
        setRefreshTokenCookie(httpResponse, result.getRefreshToken());

        return ResponseEntity.status(201).body(new AuthResponse(
                result.getUser(), result.getAccessToken(), result.getCsrfToken(), result.getSessionId()));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@RequestBody LoginRequest request, HttpServletRequest httpRequest,
                                               HttpServletResponse httpResponse) {
        AuthResult result = authService.login(request, httpRequest.getHeader("User-Agent"));
        setRefreshTokenCookie(httpResponse, result.getRefreshToken());

        return ResponseEntity.ok(new AuthResponse(
                result.getUser(), result.getAccessToken(), result.getCsrfToken(), result.getSessionId()));
    }

    @GetMapping("/refresh-token")
    public ResponseEntity<Map<String, String>> fetchRefreshToken(@CookieValue(value = "refreshToken", required = false) String refreshToken) {
        if (refreshToken == null) {
            throw new ApiException("Refresh token not found", 404);
        }
        return ResponseEntity.ok(Map.of("refreshToken", refreshToken));
    }

    @PostMapping("/refresh-token/secure")
    public ResponseEntity<Map<String, String>> refreshToken(
            @CookieValue(value = "refreshToken", required = false) String refreshToken,
            @RequestHeader(value = "x-session-id", required = false) String sessionId,
            @RequestHeader(value = "x-xsrf-token", required = false) String csrfToken) {

        authService.verifyCsrf(refreshToken, sessionId, csrfToken);
        String newAccessToken = authService.refreshAccessToken(refreshToken);

        return ResponseEntity.ok(Map.of("accessToken", newAccessToken));
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout(@RequestBody(required = false) Map<String, String> body,
                                                        @RequestHeader(value = "x-session-id", required = false) String sessionIdHeader,
                                                        HttpServletResponse httpResponse) {
        String sessionId = (body != null ? body.get("sessionId") : null);
        if (sessionId == null) {
            sessionId = sessionIdHeader;
        }

        authService.logout(sessionId);
        clearRefreshTokenCookie(httpResponse);

        return ResponseEntity.ok(Map.of("message", "Logged out successfully"));
    }

    private void setRefreshTokenCookie(HttpServletResponse response, String refreshToken) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from("refreshToken", refreshToken)
                .httpOnly(true)
                .secure(true)
                .sameSite("None")
                .path("/")
                .maxAge(7L * 24 * 60 * 60);
        if (cookieDomain != null && !cookieDomain.isBlank()) {
            builder.domain(cookieDomain);
        }
        response.addHeader("Set-Cookie", builder.build().toString());
    }

    private void clearRefreshTokenCookie(HttpServletResponse response) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from("refreshToken", "")
                .httpOnly(true)
                .secure(true)
                .sameSite("None")
                .path("/")
                .maxAge(0);
        if (cookieDomain != null && !cookieDomain.isBlank()) {
            builder.domain(cookieDomain);
        }
        response.addHeader("Set-Cookie", builder.build().toString());
    }
}
