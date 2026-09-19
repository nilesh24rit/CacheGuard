package com.cacheguard.service;

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
}
