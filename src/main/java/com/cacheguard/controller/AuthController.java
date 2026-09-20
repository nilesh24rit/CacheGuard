package com.cacheguard.controller;

import com.cacheguard.model.LoginRequest;
import com.cacheguard.model.LoginResult;
import com.cacheguard.service.LoginEventService;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes the {@code POST /api/login} endpoint.
 * Auth is simulated – a small hardcoded map decides success vs failure.
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

    public AuthController(LoginEventService loginEventService) {
        this.loginEventService = loginEventService;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResult> login(@Valid @RequestBody LoginRequest request) {
        String expected = VALID_CREDENTIALS.get(request.username());
        boolean success = expected != null && expected.equals(request.password());

        LoginResult result = new LoginResult(
                success,
                request.username(),
                success ? "Authentication successful" : "Invalid credentials",
                Instant.now()
        );

        loginEventService.recordAttempt(request, success);

        return ResponseEntity.ok(result);
    }
}
