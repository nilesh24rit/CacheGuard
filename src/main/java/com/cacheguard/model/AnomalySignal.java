package com.cacheguard.model;

/**
 * Lightweight anomaly snapshot for a single user.
 *
 * @param deviceCount              estimated distinct device count from HyperLogLog
 * @param isKnownCredentialPattern true when the credential hash already exists in the Bloom filter
 */
public record AnomalySignal(int deviceCount, boolean isKnownCredentialPattern) {
}
