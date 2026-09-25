package com.cacheguard.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalised configuration for the risk-scoring subsystem.
 *
 * <p>All values live under the {@code cacheguard.risk} prefix in
 * {@code application.yml}. The two score thresholds are read by the
 * login risk gate in
 * {@link com.cacheguard.controller.AuthController}.</p>
 */
@ConfigurationProperties(prefix = "cacheguard.risk")
public record RiskProperties(
        /** Distinct devices (HLL count) above which device-spike points are awarded. */
        int deviceCountThreshold,
        /** Score at or above which a CAPTCHA challenge is required. */
        double allowScoreMax,
        /** Score at or above which the attempt is blocked outright with 403. */
        double captchaScoreMax
) {
}
