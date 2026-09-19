package com.cacheguard;

import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared Testcontainers configuration that starts a Redis container once
 * and exposes its host/port via Spring dynamic properties so every
 * integration test in this module can connect to the same instance.
 */
@Configuration
public class TestRedisConfig {

    private static final GenericContainer<?> redisContainer;

    static {
        GenericContainer<?> container = null;
        try {
            if (DockerClientFactory.instance().isDockerAvailable()) {
                container = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                        .withExposedPorts(6379);
                container.start();
            }
        } catch (Throwable ignored) {
            // Docker not available – fall back to localhost
        }
        redisContainer = container;
    }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        if (redisContainer != null && redisContainer.isRunning()) {
            registry.add("spring.data.redis.host", redisContainer::getHost);
            registry.add("spring.data.redis.port", () -> redisContainer.getMappedPort(6379));
        } else {
            registry.add("spring.data.redis.host", () -> "localhost");
            registry.add("spring.data.redis.port", () -> 6379);
        }
    }
}
