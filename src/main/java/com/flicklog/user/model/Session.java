package com.flicklog.user.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Mirrors the embedded SessionSchema in user.model.js.
 * One of these is created per login/register and stored inside User.sessions.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Session {
    private String csrfToken;
    private String refreshToken;
    private String sessionId;
    private String userAgent;
    private Instant createdAt = Instant.now();
}
