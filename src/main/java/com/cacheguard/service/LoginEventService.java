package com.cacheguard.service;

import com.cacheguard.model.LoginRequest;
import org.springframework.stereotype.Service;

/**
 * Handles the persistence side of login attempts.
 * Initially just a stub – Redis Streams, HyperLogLog and Bloom filter
 * logic will be layered in by later commits.
 */
@Service
public class LoginEventService {

    /**
     * Record a single login attempt.  Will push to Redis Streams in a later commit.
     */
    public void recordAttempt(LoginRequest request, boolean success) {
        // placeholder – wired up in the next commits
    }
}
