package com.cacheguard.worker;

import com.cacheguard.config.RiskProperties;
import com.cacheguard.model.AnomalySignal;
import com.cacheguard.service.AnomalyDetectionService;
import com.cacheguard.service.LoginEventService;
import com.cacheguard.service.RiskService;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically scans recent login stream events for every active user
 * and evaluates anomaly signals.
 *
 * <p>Runs every 5 seconds. Reads the set of active usernames tracked by
 * {@link LoginEventService}, pulls the latest entries from each user's
 * Redis Stream, and feeds them through
 * {@link AnomalyDetectionService}.</p>
 */
@Component
public class StreamConsumerWorker {

    private static final Logger log = Logger.getLogger(StreamConsumerWorker.class.getName());

    private static final String STREAM_PREFIX = "login:stream:";

    // Points awarded when device count exceeds the threshold
    private static final double DEVICE_THRESHOLD_POINTS = 25.0;
    // Points awarded for a known credential pattern
    private static final double CREDENTIAL_PATTERN_POINTS = 15.0;

    private final LoginEventService loginEventService;
    private final AnomalyDetectionService anomalyDetectionService;
    private final RiskService riskService;
    private final RiskProperties riskProperties;
    private final StringRedisTemplate redisTemplate;

    public StreamConsumerWorker(LoginEventService loginEventService,
                                AnomalyDetectionService anomalyDetectionService,
                                RiskService riskService,
                                RiskProperties riskProperties,
                                StringRedisTemplate redisTemplate) {
        this.loginEventService = loginEventService;
        this.anomalyDetectionService = anomalyDetectionService;
        this.riskService = riskService;
        this.riskProperties = riskProperties;
        this.redisTemplate = redisTemplate;
    }

    @Scheduled(fixedRate = 5000)
    public void pollStreams() {
        Set<String> activeUsers = loginEventService.getActiveUsernames();
        if (activeUsers == null || activeUsers.isEmpty()) {
            return;
        }

        for (String username : activeUsers) {
            String streamKey = STREAM_PREFIX + username;

            // Read the latest 10 entries (most recent first)
            var entries = redisTemplate.<String, String>opsForStream().reverseRange(streamKey,
                    org.springframework.data.domain.Range.unbounded(),
                    org.springframework.data.redis.connection.Limit.limit().count(10));
            if (entries == null || entries.isEmpty()) {
                continue;
            }

            for (var entry : entries) {
                Map<String, String> body = entry.getValue();
                String deviceId = body.getOrDefault("deviceId", "unknown");
                String result = body.getOrDefault("result", "false");
                String timestamp = body.getOrDefault("timestamp", "unknown");

                // For anomaly evaluation we check the credential pattern using
                // the most recent failed attempt's implied username context.
                AnomalySignal signal = anomalyDetectionService.evaluate(username, deviceId);

                // Accumulate risk score based on anomaly signals
                if (signal.deviceCount() > riskProperties.deviceCountThreshold()) {
                    riskService.updateRiskScore(username, DEVICE_THRESHOLD_POINTS);
                    log.info(String.format(
                            "[StreamWorker] user=%s device spike detected (%d > %d), +%d risk pts",
                            username, signal.deviceCount(), riskProperties.deviceCountThreshold(),
                            (int) DEVICE_THRESHOLD_POINTS));
                }

                if (signal.isKnownCredentialPattern()) {
                    riskService.updateRiskScore(username, CREDENTIAL_PATTERN_POINTS);
                    log.info(String.format(
                            "[StreamWorker] user=%s known credential pattern, +%d risk pts",
                            username, (int) CREDENTIAL_PATTERN_POINTS));
                }

                double currentRisk = riskService.getRiskScore(username);
                log.info(String.format(
                        "[StreamWorker] user=%s device=%s result=%s ts=%s | devices=%d knownCred=%b risk=%.1f",
                        username, deviceId, result, timestamp,
                        signal.deviceCount(), signal.isKnownCredentialPattern(), currentRisk));
            }
        }
    }
}
