package com.flicklog.common.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import com.flicklog.common.config.AppProperties;

import java.time.Duration;
import java.util.HashSet;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;

import java.nio.charset.StandardCharsets;
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
    private final AppProperties appProperties;

    public RedisCacheService(StringRedisTemplate redisTemplate, AppProperties appProperties) {
        this.redisTemplate = redisTemplate;
        this.appProperties = appProperties;
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
        set(key, value, appProperties.getCache().getExpirySeconds());
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

    // Mirrors the `redis.keys('posts:*')` + bulk delete pattern used after every post mutation.
// Uses SCAN with a cursor instead of KEYS - KEYS blocks the entire Redis
// instance while it walks the whole keyspace in one shot, which is fine for
// a handful of keys locally but a known way to stall production Redis under
// load. SCAN walks it incrementally across multiple round-trips instead.
    public void deleteByPattern(String pattern) {
        try {
            Set<String> keys = scanKeys(pattern);
            if (!keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
        } catch (Exception e) {
            log.warn("Redis pattern delete failed for {}: {}", pattern, e.getMessage());
        }
    }

    private Set<String> scanKeys(String pattern) {
        Set<String> keys = new HashSet<>();
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            try (Cursor<byte[]> cursor = connection.scan(
                    ScanOptions.scanOptions().match(pattern).count(200).build())) {
                while (cursor.hasNext()) {
                    keys.add(new String(cursor.next(), StandardCharsets.UTF_8));
                }
            }
            return null;
        });
        return keys;
    }
}
