package com.cacheguard.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Lightweight operational counters backed by Redis INCR.
 *
 * <p>Three monotonic counters are maintained under the {@code stats:}
 * namespace:</p>
 * <ul>
 *   <li>{@code stats:requests:total}   – every /api request seen by the gateway</li>
 *   <li>{@code stats:requests:blocked} – requests rejected (rate limit or risk gate)</li>
 *   <li>{@code stats:logins:flagged}   – logins that were flagged for CAPTCHA</li>
 * </ul>
 *
 * <p>Increments happen at the relevant points in
 * {@link com.cacheguard.filter.RateLimiterFilter} and
 * {@link com.cacheguard.controller.AuthController}.</p>
 */
@Service
public class StatsService {

    public static final String TOTAL_REQUESTS_KEY = "stats:requests:total";
    public static final String BLOCKED_REQUESTS_KEY = "stats:requests:blocked";
    public static final String FLAGGED_LOGINS_KEY = "stats:logins:flagged";

    private final StringRedisTemplate redisTemplate;

    public StatsService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** INCR the total-request counter. */
    public void incrementTotalRequests() {
        redisTemplate.opsForValue().increment(TOTAL_REQUESTS_KEY);
    }

    /** INCR the blocked-request counter (rate-limit rejections and risk blocks). */
    public void incrementBlockedRequests() {
        redisTemplate.opsForValue().increment(BLOCKED_REQUESTS_KEY);
    }

    /** INCR the flagged-login counter (CAPTCHA challenges issued). */
    public void incrementFlaggedLogins() {
        redisTemplate.opsForValue().increment(FLAGGED_LOGINS_KEY);
    }

    /** Read the total-request counter (0 when never incremented). */
    public long getTotalRequests() {
        return read(TOTAL_REQUESTS_KEY);
    }

    /** Read the blocked-request counter (0 when never incremented). */
    public long getBlockedRequests() {
        return read(BLOCKED_REQUESTS_KEY);
    }

    /** Read the flagged-login counter (0 when never incremented). */
    public long getFlaggedLogins() {
        return read(FLAGGED_LOGINS_KEY);
    }

    private long read(String key) {
        String value = redisTemplate.opsForValue().get(key);
        return value == null ? 0L : Long.parseLong(value);
    }
}
