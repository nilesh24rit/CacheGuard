package com.cacheguard.service;

import com.cacheguard.config.ScalarCommandOutput;
import com.cacheguard.model.LoginRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.redis.connection.lettuce.LettuceConnection;
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
    private static final String BLOOM_KEY = "creds:bloom:attempts";
    private static final String ACTIVE_USERS_KEY = "active:usernames";

    private final StringRedisTemplate redisTemplate;

    public LoginEventService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Push a login attempt event into the per-user Redis Stream
     * and register the device in the user's HyperLogLog.
     *
     * <p>Also adds the username:password hash to the global credential
     * Bloom filter and records, as the {@code knownCredential} stream
     * field, whether that hash had already been seen before this attempt
     * (BF.ADD replies 0 when the item was already present).</p>
     *
     * @param request the original login payload
     * @param success whether authentication succeeded
     */
    public void recordAttempt(LoginRequest request, boolean success) {
        // --- Bloom filter BF.ADD for credential hash ---
        // BF.* is unknown to Spring Data Redis, so the default byte-array
        // output cannot decode its integer/boolean reply. Run it on the raw
        // LettuceConnection with ScalarCommandOutput (template callbacks only
        // expose the RedisConnection interface, which lacks that overload).
        // BF.ADD replies 1 when the hash was newly added and 0 when it was
        // already present, so the reply itself tells us whether this exact
        // username:password pair had been seen before this attempt - the
        // "known credential" verdict the worker consumes later. It must be
        // taken here, before the hash is stored: checking the filter after
        // the add would always report "known".
        String credHash = sha256Hex(request.username() + ":" + request.password());
        LettuceConnection rawConnection =
                (LettuceConnection) redisTemplate.getConnectionFactory().getConnection();
        boolean knownCredential;
        try {
            Object reply = rawConnection.execute(
                    "BF.ADD",
                    new ScalarCommandOutput(),
                    BLOOM_KEY.getBytes(StandardCharsets.UTF_8),
                    credHash.getBytes(StandardCharsets.UTF_8));
            knownCredential = (reply instanceof Number number)
                    && number.longValue() == 0L;
        } finally {
            rawConnection.close();
        }

        // --- Redis Stream XADD ---
        String streamKey = STREAM_PREFIX + request.username();
        Map<String, String> fields = Map.of(
                "ip", request.ip() != null ? request.ip() : "unknown",
                "deviceId", request.deviceId() != null ? request.deviceId() : "unknown",
                "result", String.valueOf(success),
                "timestamp", Instant.now().toString(),
                "knownCredential", String.valueOf(knownCredential)
        );
        redisTemplate.opsForStream().add(streamKey, fields);

        // --- HyperLogLog PFADD (raw command via execute) ---
        String hllKey = HLL_PREFIX + request.username();
        String deviceId = request.deviceId() != null ? request.deviceId() : "unknown";
        redisTemplate.execute((org.springframework.data.redis.core.RedisCallback<Long>) connection ->
                (Long) connection.execute(
                        "PFADD",
                        hllKey.getBytes(StandardCharsets.UTF_8),
                        deviceId.getBytes(StandardCharsets.UTF_8)
                ));

        // --- Track active username in a Redis Set ---
        redisTemplate.opsForSet().add(ACTIVE_USERS_KEY, request.username());
    }

    /**
     * Returns the set of usernames that have had recent login activity.
     * Used by the scheduled worker to know which streams to scan.
     */
    public java.util.Set<String> getActiveUsernames() {
        return redisTemplate.opsForSet().members(ACTIVE_USERS_KEY);
    }

    /**
     * Read recent login events for a user from their Redis Stream
     * using XRANGE, newest first.
     *
     * @param username target user
     * @param limit    maximum number of events to return
     * @return event maps with timestamp, ip, deviceId and result
     */
    public List<Map<String, Object>> getRecentEvents(String username, int limit) {
        String streamKey = STREAM_PREFIX + username;

        // XRANGE over the whole stream, then keep only the tail
        var entries = redisTemplate.<String, String>opsForStream()
                .range(streamKey, org.springframework.data.domain.Range.unbounded());
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }

        int from = Math.max(0, entries.size() - limit);
        List<Map<String, Object>> events = new ArrayList<>();
        for (int i = entries.size() - 1; i >= from; i--) { // newest first
            Map<String, String> body = entries.get(i).getValue();
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("timestamp", body.getOrDefault("timestamp", "unknown"));
            event.put("ip", body.getOrDefault("ip", "unknown"));
            event.put("deviceId", body.getOrDefault("deviceId", "unknown"));
            event.put("result", body.getOrDefault("result", "false"));
            events.add(event);
        }
        return events;
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
