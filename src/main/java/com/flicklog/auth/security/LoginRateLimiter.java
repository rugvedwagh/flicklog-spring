package com.flicklog.auth.security;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory login-attempt tracker. Deliberately not backed by Redis or Mongo -
 * this app runs as a single instance, so a plain in-JVM map is simpler and more
 * reliable than depending on an external store that may not always be running.
 * Counters reset on app restart; that's an acceptable trade-off here, not an oversight.
 */
@Component
public class LoginRateLimiter {

    private static final int MAX_ATTEMPTS = 5;
    private static final Duration LOCKOUT = Duration.ofMinutes(15);

    private record AttemptWindow(int count, Instant lockedUntil) {}

    private final ConcurrentHashMap<String, AttemptWindow> attempts = new ConcurrentHashMap<>();

    public boolean isBlocked(String key) {
        AttemptWindow window = attempts.get(key);
        if (window == null) {
            return false;
        }
        if (window.lockedUntil() != null && Instant.now().isBefore(window.lockedUntil())) {
            return true;
        }
        // Lockout has expired - clean up so this key doesn't linger forever.
        attempts.remove(key, window);
        return false;
    }

    public void recordFailure(String key) {
        attempts.compute(key, (k, existing) -> {
            int newCount = (existing == null ? 0 : existing.count()) + 1;
            Instant lockedUntil = newCount >= MAX_ATTEMPTS ? Instant.now().plus(LOCKOUT) : null;
            return new AttemptWindow(newCount, lockedUntil);
        });
    }

    public void reset(String key) {
        attempts.remove(key);
    }
}