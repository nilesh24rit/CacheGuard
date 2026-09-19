package com.cacheguard.model;

/**
 * Immutable value object returned by {@link com.cacheguard.service.RateLimiterService}.
 *
 * @param allowed  {@code true} when the request count is within the configured limit
 * @param count    the number of requests seen in the current sliding window
 */
public record RateLimitResult(boolean allowed, long count) {
}
