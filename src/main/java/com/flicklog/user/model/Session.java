package com.flicklog.user.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Mirrors the embedded SessionSchema in user.model.js.
 * One of these is created per login/register and stored inside User.sessions.
 */
@Data
@NoArgsConstructor
public class Session {
    private String csrfToken;
    private String refreshToken;
    private String sessionId;
    private String userAgent;
    private Instant createdAt = Instant.now();

    // A very short overlap prevents two simultaneous refresh requests from the
    // same browser from being treated as token theft after the first rotates it.
    private String previousRefreshToken;
    private Instant previousRefreshValidUntil;

    public Session(String csrfToken, String refreshToken, String sessionId, String userAgent, Instant createdAt) {
        this.csrfToken = csrfToken;
        this.refreshToken = refreshToken;
        this.sessionId = sessionId;
        this.userAgent = userAgent;
        this.createdAt = createdAt;
    }
}
