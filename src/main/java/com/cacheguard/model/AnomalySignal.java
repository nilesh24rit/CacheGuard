package com.cacheguard.model;

/**
 * Lightweight anomaly snapshot for a single user.
 *
 * @param deviceCount              estimated distinct device count from HyperLogLog
 * @param isKnownCredentialPattern true when the credential had already been seen
 *                                 before the attempt was recorded (record-time
 *                                 Bloom filter verdict carried by the event)
 */
public record AnomalySignal(int deviceCount, boolean isKnownCredentialPattern) {
}
