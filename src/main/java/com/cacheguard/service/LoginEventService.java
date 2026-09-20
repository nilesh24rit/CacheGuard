package com.cacheguard.service;

import com.cacheguard.model.LoginRequest;
import java.time.Instant;
import java.util.Map;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Persists login attempts into Redis Streams so downstream consumers
 * can process them for anomaly detection. Also maintains a per-user
 * HyperLogLog of distinct device IDs.
 */
@Service
public class LoginEventService {

    private static final String STREAM_PREFIX = "login:stream:";
    private static final String HLL_PREFIX = "devices:hll:";

    private final StringRedisTemplate redisTemplate;

    public LoginEventService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Push a login attempt event into the per-user Redis Stream
     * and register the device in the user's HyperLogLog.
     *
     * @param request the original login payload
     * @param success whether authentication succeeded
     */
    public void recordAttempt(LoginRequest request, boolean success) {
        // --- Redis Stream XADD ---
        String streamKey = STREAM_PREFIX + request.username();
        Map<String, String> fields = Map.of(
                "ip", request.ip() != null ? request.ip() : "unknown",
                "deviceId", request.deviceId() != null ? request.deviceId() : "unknown",
                "result", String.valueOf(success),
                "timestamp", Instant.now().toString()
        );
        redisTemplate.opsForStream().add(streamKey, fields);

        // --- HyperLogLog PFADD (raw command via execute) ---
        String hllKey = HLL_PREFIX + request.username();
        String deviceId = request.deviceId() != null ? request.deviceId() : "unknown";
        redisTemplate.execute((org.springframework.data.redis.core.RedisCallback<Long>) connection ->
                connection.execute(
                        "PFADD",
                        hllKey.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        deviceId.getBytes(java.nio.charset.StandardCharsets.UTF_8)
                ));
    }
}
