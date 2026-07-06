package com.flicklog.security;

import com.flicklog.config.JwtProperties;
import com.flicklog.model.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;

/**
 * Mirrors utils/generate-tokens.js (generateAccessToken / generateRefreshToken / generateCsrfToken).
 */
@Service
public class JwtTokenService {

    private final JwtProperties jwtProperties;
    private final SecureRandom secureRandom = new SecureRandom();

    public JwtTokenService(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
    }

    private SecretKey accessKey() {
        return Keys.hmacShaKeyFor(jwtProperties.getAccessSecret().getBytes(StandardCharsets.UTF_8));
    }

    private SecretKey refreshKey() {
        return Keys.hmacShaKeyFor(jwtProperties.getRefreshSecret().getBytes(StandardCharsets.UTF_8));
    }

    public String generateAccessToken(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .claim("email", user.getEmail())
                .claim("id", user.getId())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(Duration.ofMinutes(jwtProperties.getAccessExpiryMinutes()))))
                .signWith(accessKey())
                .compact();
    }

    public String generateRefreshToken(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .claim("id", user.getId())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(Duration.ofDays(jwtProperties.getRefreshExpiryDays()))))
                .signWith(refreshKey())
                .compact();
    }

    public String generateCsrfToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    public Claims verifyAccessToken(String token) {
        return Jwts.parser().verifyWith(accessKey()).build()
                .parseSignedClaims(token).getPayload();
    }

    public Claims verifyRefreshToken(String token) {
        return Jwts.parser().verifyWith(refreshKey()).build()
                .parseSignedClaims(token).getPayload();
    }
}
