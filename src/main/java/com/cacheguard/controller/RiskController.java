package com.cacheguard.controller;

import com.cacheguard.service.RiskService;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes read-only endpoints for inspecting per-user risk data.
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
}
