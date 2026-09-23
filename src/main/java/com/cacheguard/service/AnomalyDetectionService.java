package com.cacheguard.service;

import com.cacheguard.model.AnomalySignal;
import java.nio.charset.StandardCharsets;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Reads the per-user device HyperLogLog to produce an {@link AnomalySignal}.
 *
 * <p>The credential side of the signal - "was this username:password pair
 * already known?" - is decided once, at record time, by
 * {@link LoginEventService} (from the BF.ADD reply) and travels with the
 * stream event as its {@code knownCredential} field. Re-checking the Bloom
 * filter here, after the hash has been stored, would always report
 * "known", and the worker never has the plaintext password anyway.</p>
 */
@Service
public class AnomalyDetectionService {

    private static final String HLL_PREFIX = "devices:hll:";

    private final StringRedisTemplate redisTemplate;

    public AnomalyDetectionService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Run PFCOUNT on the user's device HyperLogLog and combine the device
     * count with the record-time known-credential verdict carried by the
     * event.
     *
     * @param username        target user
     * @param knownCredential true when this event's credential had already
     *                        been seen before the attempt was recorded
     * @return anomaly signal for this event
     */
    public AnomalySignal evaluate(String username, boolean knownCredential) {
        int deviceCount = redisTemplate.execute(
                (org.springframework.data.redis.core.RedisCallback<Long>) connection -> {
                    Long count = connection.pfCount(
                            (HLL_PREFIX + username).getBytes(StandardCharsets.UTF_8));
                    return count != null ? count : 0L;
                }).intValue();

        return new AnomalySignal(deviceCount, knownCredential);
    }
}
