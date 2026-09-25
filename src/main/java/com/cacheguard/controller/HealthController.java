package com.cacheguard.controller;

import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liveness endpoint: {@code GET /api/health} issues a Redis {@code PING}
 * and answers {@code OK} when the connection works.
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    private final StringRedisTemplate stringRedisTemplate;

    public HealthController(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * Redis-backed health probe.
     *
     * @return literal {@code OK} when Redis answers the PING
     */
    @GetMapping("/health")
    public String health() {
        stringRedisTemplate.execute((RedisCallback<String>) connection -> connection.ping());
        return "OK";
    }
}
