package com.cacheguard.model;

import java.time.Instant;

/**
 * Response returned after a login attempt is processed.
 */
public record LoginResult(
        boolean success,
        String username,
        String message,
        Instant timestamp,
        boolean requiresCaptcha
) {
}
