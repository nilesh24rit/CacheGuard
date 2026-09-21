package com.cacheguard.controller;

import com.cacheguard.service.RiskService;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes read-only endpoints for inspecting per-user risk data
 * and the global risk hotlist.
 */
@RestController
@RequestMapping("/api")
public class RiskController {

    private final RiskService riskService;

    public RiskController(RiskService riskService) {
        this.riskService = riskService;
    }

    /**
     * Returns the current risk score for a given user.
     *
     * @param username target user
     * @return JSON object containing the username and its score
     */
    @GetMapping("/risk/{username}")
    public ResponseEntity<Map<String, Object>> getRiskScore(@PathVariable String username) {
        double score = riskService.getRiskScore(username);
        return ResponseEntity.ok(Map.of(
                "username", username,
                "score", score
        ));
    }

    /**
     * Returns the top N riskiest users from the global hotlist,
     * ordered from highest score to lowest.
     *
     * @param top number of entries to return (defaults to 10)
     * @return JSON list of username-score pairs
     */
    @GetMapping("/hotlist")
    public ResponseEntity<Map<String, Object>> getHotlist(
            @RequestParam(defaultValue = "10") int top) {
        List<Map<String, Object>> entries = riskService.getTopRiskyUsers(top);
        return ResponseEntity.ok(Map.of(
                "count", entries.size(),
                "entries", entries
        ));
    }
}
