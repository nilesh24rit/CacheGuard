package com.cacheguard.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Manages per-user risk scores stored in a Redis sorted set.
 *
 * <p>Each username is a member of the {@code risk:hotlist} sorted set.
 * The score represents an accumulated risk value – higher is riskier.
 * {@link #updateRiskScore} increments the score via ZINCRBY and
 * {@link #getRiskScore} reads the current value via ZSCORE.</p>
 */
@Service
public class RiskService {

    private static final String RISK_HOTLIST_KEY = "risk:hotlist";

    private final StringRedisTemplate redisTemplate;

    public RiskService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Increment the risk score for the given user.
     *
     * @param username target user
     * @param points   delta to add (positive increases risk)
     * @return the new score after increment
     */
    public double updateRiskScore(String username, double points) {
        Double newScore = redisTemplate.opsForZSet().incrementScore(
                RISK_HOTLIST_KEY, username, points);
        return newScore != null ? newScore : 0.0;
    }

    /**
     * Fetch the current risk score for a user.
     *
     * @param username target user
     * @return the score, or 0.0 if the user has no entry
     */
    public double getRiskScore(String username) {
        Double score = redisTemplate.opsForZSet().score(RISK_HOTLIST_KEY, username);
        return score != null ? score : 0.0;
    }

    /**
     * Return the top N riskiest usernames with their scores,
     * ordered from highest score to lowest.
     *
     * @param top number of entries to retrieve
     * @return ordered list of username-to-score mappings
     */
    public List<Map<String, Object>> getTopRiskyUsers(long top) {
        var entries = redisTemplate.opsForZSet().reverseRangeWithScores(
                RISK_HOTLIST_KEY, 0, top - 1);
        if (entries == null) {
            return List.of();
        }

        return entries.stream()
                .map(entry -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("username", entry.getValue());
                    map.put("score", entry.getScore());
                    return map;
                })
                .toList();
    }
}
