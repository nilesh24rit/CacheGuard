package com.cacheguard.worker;

import com.cacheguard.config.RiskProperties;
import com.cacheguard.model.AnomalySignal;
import com.cacheguard.service.AnomalyDetectionService;
import com.cacheguard.service.LoginEventService;
import com.cacheguard.service.RiskService;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically scans recent login stream events for every active user
 * and evaluates anomaly signals.
 *
 * <p>Runs every 5 seconds. Reads the set of active usernames tracked by
 * {@link LoginEventService}, then drains each user's Redis Stream from a
 * per-user cursor ({@code worker:lastid:<username>}) so every new entry is
 * scored exactly once - the same events are never re-awarded on later
 * ticks. Each entry is fed through
 * {@link AnomalyDetectionService} together with its record-time
 * {@code knownCredential} verdict.</p>
 */
@Component
public class StreamConsumerWorker {

    private static final Logger log = Logger.getLogger(StreamConsumerWorker.class.getName());

    private static final String STREAM_PREFIX = "login:stream:";
    // Per-user cursor holding the ID of the last stream entry that was scored.
    private static final String LAST_ID_PREFIX = "worker:lastid:";
    // Upper bound of newly scored events per user per tick.
    private static final int MAX_EVENTS_PER_POLL = 100;

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

    /**
     * Scheduled every 5 seconds: drain and score any login events that
     * arrived since this user's cursor, then advance the cursor.
     */
    @Scheduled(fixedRate = 5000)
    public void pollStreams() {
        Set<String> activeUsers = loginEventService.getActiveUsernames();
        if (activeUsers == null || activeUsers.isEmpty()) {
            return;
        }

        for (String username : activeUsers) {
            String streamKey = STREAM_PREFIX + username;
            String lastIdKey = LAST_ID_PREFIX + username;
            String lastProcessedId = redisTemplate.opsForValue().get(lastIdKey);

            // Read the whole stream oldest-first so the cursor only moves
            // forward; already-scored entries are skipped below.
            var entries = redisTemplate.<String, String>opsForStream()
                    .range(streamKey, Range.unbounded());
            if (entries == null || entries.isEmpty()) {
                continue;
            }

            int processed = 0;
            String newestProcessedId = null;
            for (var entry : entries) {
                if (!isNewer(entry.getId().getValue(), lastProcessedId)) {
                    continue; // scored in an earlier tick
                }
                if (processed >= MAX_EVENTS_PER_POLL) {
                    break; // leave the rest for the next tick
                }

                Map<String, String> body = entry.getValue();
                String deviceId = body.getOrDefault("deviceId", "unknown");
                String result = body.getOrDefault("result", "false");
                String timestamp = body.getOrDefault("timestamp", "unknown");
                // Record-time verdict stored by LoginEventService; do not
                // re-derive it from the Bloom filter here (the hash has
                // already been added by the time this event is read).
                boolean knownCredential = Boolean.parseBoolean(
                        body.getOrDefault("knownCredential", "false"));

                AnomalySignal signal =
                        anomalyDetectionService.evaluate(username, knownCredential);

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

                newestProcessedId = entry.getId().getValue();
                processed++;
            }

            // Advance the cursor only for entries that were actually scored,
            // so each event contributes its points exactly once.
            if (newestProcessedId != null) {
                redisTemplate.opsForValue().set(lastIdKey, newestProcessedId);
            }
        }
    }

    /**
     * Compare Redis stream entry IDs of the form {@code <milliseconds>-<sequence>}.
     *
     * @param id               candidate entry ID
     * @param lastProcessedId  cursor value, {@code null} when nothing scored yet
     * @return true when {@code id} is newer than the cursor
     */
    private static boolean isNewer(String id, String lastProcessedId) {
        if (lastProcessedId == null || lastProcessedId.isEmpty()) {
            return true;
        }
        String[] current = id.split("-");
        String[] previous = lastProcessedId.split("-");
        long currentMs = Long.parseLong(current[0]);
        long previousMs = Long.parseLong(previous[0]);
        if (currentMs != previousMs) {
            return currentMs > previousMs;
        }
        return Long.parseLong(current[1]) > Long.parseLong(previous[1]);
    }
}
