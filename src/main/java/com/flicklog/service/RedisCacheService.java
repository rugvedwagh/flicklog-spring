package com.flicklog.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Set;

/**
 * Mirrors config/redis.js's role: cache lookups never fail the request if
 * Redis is unreachable - every call here swallows exceptions and returns
 * null/false, exactly like the original's `redisAvailable` guard.
 */
@Slf4j
@Service
public class RedisCacheService {

    private final StringRedisTemplate redisTemplate;

    @Value("${app.cache.expiry-seconds:300}")
    private long defaultExpirySeconds;

    public RedisCacheService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public String get(String key) {
        try {
            return redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.warn("Redis GET failed for key {}: {}", key, e.getMessage());
            return null;
        }
    }

    public void set(String key, String value) {
        set(key, value, defaultExpirySeconds);
    }

    public void set(String key, String value, long expirySeconds) {
        try {
            redisTemplate.opsForValue().set(key, value, Duration.ofSeconds(expirySeconds));
        } catch (Exception e) {
            log.warn("Redis SET failed for key {}: {}", key, e.getMessage());
        }
    }

    public void delete(String key) {
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.warn("Redis DEL failed for key {}: {}", key, e.getMessage());
        }
    }

    // Mirrors the `redis.keys('posts:*')` + bulk delete pattern used after every post mutation
    public void deleteByPattern(String pattern) {
        try {
            Set<String> keys = redisTemplate.keys(pattern);
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
        } catch (Exception e) {
            log.warn("Redis pattern delete failed for {}: {}", pattern, e.getMessage());
        }
    }
}
