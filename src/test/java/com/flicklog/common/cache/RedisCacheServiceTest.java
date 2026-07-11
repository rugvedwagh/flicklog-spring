package com.flicklog.common.cache;

import com.flicklog.common.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RedisCacheServiceTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private AppProperties appProperties;

    private RedisCacheService redisCacheService;

    @BeforeEach
    void setUp() {
        redisCacheService = new RedisCacheService(redisTemplate, appProperties);
    }

    @Test
    void deleteByPattern_scansAndDeletesMatchingKeys() {
        RedisConnection connection = mock(RedisConnection.class);
        @SuppressWarnings("unchecked")
        Cursor<byte[]> cursor = mock(Cursor.class);
        when(cursor.hasNext()).thenReturn(true, true, false);
        when(cursor.next()).thenReturn(
                "posts:page:1".getBytes(StandardCharsets.UTF_8),
                "posts:page:2".getBytes(StandardCharsets.UTF_8));
        when(connection.scan(any(ScanOptions.class))).thenReturn(cursor);

        when(redisTemplate.execute(any(RedisCallback.class))).thenAnswer(invocation -> {
            RedisCallback<?> callback = invocation.getArgument(0);
            return callback.doInRedis(connection);
        });

        redisCacheService.deleteByPattern("posts:*");

        verify(redisTemplate).delete(Set.of("posts:page:1", "posts:page:2"));
        verify(cursor).close();
    }

    @Test
    void deleteByPattern_noMatchingKeys_doesNotCallDelete() {
        RedisConnection connection = mock(RedisConnection.class);
        @SuppressWarnings("unchecked")
        Cursor<byte[]> cursor = mock(Cursor.class);
        when(cursor.hasNext()).thenReturn(false);
        when(connection.scan(any(ScanOptions.class))).thenReturn(cursor);

        when(redisTemplate.execute(any(RedisCallback.class))).thenAnswer(invocation -> {
            RedisCallback<?> callback = invocation.getArgument(0);
            return callback.doInRedis(connection);
        });

        redisCacheService.deleteByPattern("posts:*");

        verify(redisTemplate, never()).delete(any(Set.class));
    }

    // Regression test: Redis being unreachable must never fail the calling
    // request - every method here swallows exceptions, matching the
    // `redisAvailable` guard from the original app.
    @Test
    void deleteByPattern_redisUnavailable_swallowsException() {
        when(redisTemplate.execute(any(RedisCallback.class)))
                .thenThrow(new RuntimeException("Connection refused"));

        redisCacheService.deleteByPattern("posts:*"); // should not throw

        verify(redisTemplate, never()).delete(any(Set.class));
    }

    @Test
    void get_redisUnavailable_returnsNull() {
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("post:123")).thenThrow(new RuntimeException("Connection refused"));

        String result = redisCacheService.get("post:123");

        org.assertj.core.api.Assertions.assertThat(result).isNull();
    }
}