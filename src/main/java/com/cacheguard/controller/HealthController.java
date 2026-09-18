package com.cacheguard.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class HealthController {

    private final StringRedisTemplate stringRedisTemplate;

    @GetMapping("/health")
    public String health() {
        stringRedisTemplate.execute((RedisCallback<String>) connection -> connection.ping());
        return "OK";
    }
}
