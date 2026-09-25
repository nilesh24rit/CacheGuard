package com.cacheguard.service;

import com.cacheguard.model.AnomalySignal;
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
        Long count = redisTemplate.opsForHyperLogLog().size(HLL_PREFIX + username);
        int deviceCount = count != null ? count.intValue() : 0;

        return new AnomalySignal(deviceCount, knownCredential);
    }
}
