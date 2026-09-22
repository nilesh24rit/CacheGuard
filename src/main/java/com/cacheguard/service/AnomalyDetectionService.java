package com.cacheguard.service;

import com.cacheguard.config.ScalarCommandOutput;
import com.cacheguard.model.AnomalySignal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.data.redis.connection.lettuce.LettuceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Reads the per-user HyperLogLog and the global credential Bloom filter
 * to produce a simple {@link AnomalySignal}.
 */
@Service
public class AnomalyDetectionService {

    private static final String HLL_PREFIX = "devices:hll:";
    private static final String BLOOM_KEY = "creds:bloom:attempts";

    private final StringRedisTemplate redisTemplate;

    public AnomalyDetectionService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Run PFCOUNT on the user's device HLL and BF.EXISTS on the
     * credential Bloom filter for the given username+password pair.
     */
    public AnomalySignal evaluate(String username, String password) {
        // PFCOUNT on the per-user HyperLogLog
        int deviceCount = redisTemplate.execute((org.springframework.data.redis.core.RedisCallback<Long>) connection -> {
            Long count = connection.pfCount((HLL_PREFIX + username).getBytes(StandardCharsets.UTF_8));
            return count != null ? count : 0L;
        }).intValue();

        // BF.EXISTS on the credential Bloom filter. BF.* is unknown to Spring
        // Data Redis, so ScalarCommandOutput decodes the reply (integer under
        // RESP2, boolean under RESP3). Template callbacks only expose the
        // RedisConnection interface, which lacks that overload, so run the
        // command on the raw LettuceConnection.
        String credHash = sha256Hex(username + ":" + password);
        LettuceConnection rawConnection =
                (LettuceConnection) redisTemplate.getConnectionFactory().getConnection();
        long exists;
        try {
            Object result = rawConnection.execute(
                    "BF.EXISTS",
                    new ScalarCommandOutput(),
                    BLOOM_KEY.getBytes(StandardCharsets.UTF_8),
                    credHash.getBytes(StandardCharsets.UTF_8));
            exists = (result instanceof Number number) ? number.longValue() : 0L;
        } finally {
            rawConnection.close();
        }

        return new AnomalySignal(deviceCount, exists == 1L);
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
