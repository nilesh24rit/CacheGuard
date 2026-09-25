package com.cacheguard;

import static org.assertj.core.api.Assertions.assertThat;

import com.cacheguard.model.RateLimitResult;
import com.cacheguard.service.RateLimiterService;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Integration test that exercises {@link RateLimiterService} against a real
 * (Testcontainers or local) Redis instance.  The test sends more requests
 * than the configured maximum and asserts that the very next request is
 * rejected with {@code allowed == false}.
 * <p>
 * Skipped automatically when no Redis server is reachable: the condition
 * below invokes {@link TestRedisConfig#isRedisAvailable()}, which forces the
 * Redis probe to run before the skip/run decision is made.
 */
@SpringBootTest
@EnabledIf("com.cacheguard.TestRedisConfig#isRedisAvailable")
class RateLimiterServiceTest {

    private static final int MAX_REQUESTS = 3;
    private static final int WINDOW_SECONDS = 60;

    @Autowired
    private RateLimiterService rateLimiterService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void clearRateLimitKeys() {
        Set<String> keys = redisTemplate.keys("ratelimit:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @Test
    void allowsRequestsUpToMaxThenRejects() {
        // Fire exactly MAX_REQUESTS – all should be allowed
        for (int i = 0; i < MAX_REQUESTS; i++) {
            RateLimitResult result = rateLimiterService.isAllowed(
                    "127.0.0.1", "/api/test", MAX_REQUESTS, WINDOW_SECONDS);
            assertThat(result.allowed()).as("request %d should be allowed", i + 1).isTrue();
            assertThat(result.count()).isEqualTo(i + 1);
        }

        // Next request should be rejected
        RateLimitResult excessResult = rateLimiterService.isAllowed(
                "127.0.0.1", "/api/test", MAX_REQUESTS, WINDOW_SECONDS);
        assertThat(excessResult.allowed()).isFalse();
        assertThat(excessResult.count()).isEqualTo(MAX_REQUESTS + 1);
    }

    @Test
    void differentEndpointsAreTrackedIndependently() {
        RateLimitResult r1 = rateLimiterService.isAllowed(
                "127.0.0.1", "/api/alpha", MAX_REQUESTS, WINDOW_SECONDS);
        RateLimitResult r2 = rateLimiterService.isAllowed(
                "127.0.0.1", "/api/beta", MAX_REQUESTS, WINDOW_SECONDS);

        assertThat(r1.count()).isEqualTo(1);
        assertThat(r2.count()).isEqualTo(1);
    }

    @Test
    void differentIpsAreTrackedIndependently() {
        RateLimitResult fromA = rateLimiterService.isAllowed(
                "10.0.0.1", "/api/shared", MAX_REQUESTS, WINDOW_SECONDS);
        RateLimitResult fromB = rateLimiterService.isAllowed(
                "10.0.0.2", "/api/shared", MAX_REQUESTS, WINDOW_SECONDS);

        assertThat(fromA.count()).isEqualTo(1);
        assertThat(fromB.count()).isEqualTo(1);
    }
}
