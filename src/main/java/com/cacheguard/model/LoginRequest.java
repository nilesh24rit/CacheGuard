package com.cacheguard.model;

import jakarta.validation.constraints.NotBlank;

/**
 * Payload accepted by the login endpoint.
 */
public record LoginRequest(
        @NotBlank String username,
        @NotBlank String password,
        String ip,
        String deviceId
) {
}
