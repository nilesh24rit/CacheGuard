package com.cacheguard.service;

import com.cacheguard.model.LoginRequest;
import java.time.Instant;
import java.util.Map;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Persists login attempts into Redis Streams so downstream consumers
 * can process them for anomaly detection.
 */
@Service
public class LoginEventService {

    private static final String STREAM_PREFIX = "login:stream:";

    private final StringRedisTemplate redisTemplate;

    public LoginEventService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Push a login attempt event into the per-user Redis Stream.
     *
     * @param request the original login payload
     * @param success whether authentication succeeded
     */
    public void recordAttempt(LoginRequest request, boolean success) {
        String key = STREAM_PREFIX + request.username();
        Map<String, String> fields = Map.of(
                "ip", request.ip() != null ? request.ip() : "unknown",
                "deviceId", request.deviceId() != null ? request.deviceId() : "unknown",
                "result", String.valueOf(success),
                "timestamp", Instant.now().toString()
        );
        redisTemplate.opsForStream().add(key, fields);
    }
}
