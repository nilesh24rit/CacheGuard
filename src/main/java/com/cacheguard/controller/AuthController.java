package com.cacheguard.controller;

import com.cacheguard.config.RiskProperties;
import com.cacheguard.model.LoginRequest;
import com.cacheguard.model.LoginResult;
import com.cacheguard.service.LoginEventService;
import com.cacheguard.service.RiskService;
import com.cacheguard.service.StatsService;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes the {@code POST /api/login} endpoint.
 * Auth is simulated – a small hardcoded map decides success vs failure.
 * A risk-score gate runs before credential validation to decide whether
 * to proceed normally, require a CAPTCHA, or block the attempt entirely.
 */
@RestController
@RequestMapping("/api")
public class AuthController {

    private static final Map<String, String> VALID_CREDENTIALS = Map.of(
            "admin", "secret",
            "alice", "password123",
            "bob", "letmein"
    );

    private final LoginEventService loginEventService;
    private final RiskService riskService;
    private final RiskProperties riskProperties;
    private final StatsService statsService;

    public AuthController(LoginEventService loginEventService,
                          RiskService riskService,
                          RiskProperties riskProperties,
                          StatsService statsService) {
        this.loginEventService = loginEventService;
        this.riskService = riskService;
        this.riskProperties = riskProperties;
        this.statsService = statsService;
    }

    /**
     * Risk gate, then simulated credential check, then event recording.
     *
     * <p>Scores at or above {@code captchaScoreMax} are rejected with
     * {@code 403}; scores at or above {@code allowScoreMax} answer with a
     * CAPTCHA challenge; only lower scores reach credential validation and
     * {@link LoginEventService#recordAttempt}.</p>
     *
     * @param request validated login payload (username, password, ip, deviceId)
     * @return {@code 200} with a {@link LoginResult}, or {@code 403} when blocked
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        double riskScore = riskService.getRiskScore(request.username());

        // Risk gate: block if score is above the hard limit
        if (riskScore >= riskProperties.captchaScoreMax()) {
            statsService.incrementBlockedRequests();
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of(
                            "success", false,
                            "username", request.username(),
                            "message", "Account locked due to suspicious activity",
                            "riskScore", riskScore
                    ));
        }

        // Risk gate: require CAPTCHA if score is in the elevated range
        if (riskScore >= riskProperties.allowScoreMax()) {
            statsService.incrementFlaggedLogins();
            LoginResult result = new LoginResult(
                    false,
                    request.username(),
                    "CAPTCHA verification required",
                    Instant.now(),
                    true
            );
            return ResponseEntity.ok(result);
        }

        // Normal flow – score is below allowScoreMax
        String expected = VALID_CREDENTIALS.get(request.username());
        boolean success = expected != null && expected.equals(request.password());

        LoginResult result = new LoginResult(
                success,
                request.username(),
                success ? "Authentication successful" : "Invalid credentials",
                Instant.now(),
                false
        );

        loginEventService.recordAttempt(request, success);

        return ResponseEntity.ok(result);
    }
}
