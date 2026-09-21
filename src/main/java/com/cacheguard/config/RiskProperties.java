package com.cacheguard.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalised configuration for the risk-scoring subsystem.
 *
 * <p>All values live under the {@code cacheguard.risk} prefix in
 * {@code application.yml}.</p>
 */
@ConfigurationProperties(prefix = "cacheguard.risk")
public record RiskProperties(
        /** Number of distinct devices that triggers a risk point award. */
        int deviceCountThreshold,
        /** Score at or above which login attempts are silently allowed but flagged. */
        double allowScoreMax,
        /** Score at or above which a CAPTCHA challenge is required. */
        double captchaScoreMax
) {
}
