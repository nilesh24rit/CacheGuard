package com.cacheguard.service;

import com.cacheguard.model.RateLimitResult;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Service;

/**
 * Executes the sliding-window Lua script against Redis to enforce
 * per-IP, per-endpoint rate limits atomically.
 */
@Service
public class RateLimiterService {

    private final RedisTemplate<String, String> redisTemplate;
    private final RedisScript<Long> slidingWindowScript;

    public RateLimiterService(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;

        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(
                new ResourceScriptSource(new ClassPathResource("scripts/sliding_window.lua")));
        script.setResultType(Long.class);
        this.slidingWindowScript = script;
    }

    /**
     * Check whether the given client is allowed to make another request.
     *
     * @param ip            client IP address (or X-Forwarded-For first value)
     * @param endpoint      request URI path, e.g. {@code /api/health}
     * @param maxRequests   maximum number of requests allowed inside the window
     * @param windowSeconds sliding-window size in seconds
     * @return {@link RateLimitResult} with the decision and the current window count
     */
    public RateLimitResult isAllowed(String ip, String endpoint, int maxRequests, int windowSeconds) {
        String key = "ratelimit:" + ip + ":" + endpoint;
        long now = System.currentTimeMillis();
        String member = now + ":" + UUID.randomUUID();

        Long count = redisTemplate.execute(
                slidingWindowScript,
                List.of(key),
                String.valueOf(now),
                String.valueOf(windowSeconds),
                member
        );

        long currentCount = count == null ? 0L : count;
        return new RateLimitResult(currentCount <= maxRequests, currentCount);
    }
}
