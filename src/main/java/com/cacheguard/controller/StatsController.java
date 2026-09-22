package com.cacheguard.controller;

import com.cacheguard.service.RiskService;
import com.cacheguard.service.StatsService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only monitoring endpoint for gateway operators.
 *
 * <p>Returns the Redis-backed request/block/flag counters together
 * with the current size of the risk hotlist (ZCARD).</p>
 */
@RestController
@RequestMapping("/api")
public class StatsController {

    private final StatsService statsService;
    private final RiskService riskService;

    public StatsController(StatsService statsService, RiskService riskService) {
        this.statsService = statsService;
        this.riskService = riskService;
    }

    /**
     * Snapshot of the operational counters.
     *
     * @return JSON with totalRequests, blockedCount, flaggedCount, hotlistSize
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("totalRequests", statsService.getTotalRequests());
        body.put("blockedCount", statsService.getBlockedRequests());
        body.put("flaggedCount", statsService.getFlaggedLogins());
        body.put("hotlistSize", riskService.getHotlistSize());
        return ResponseEntity.ok(body);
    }
}
