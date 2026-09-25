package com.cacheguard;

import java.net.Socket;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared Testcontainers configuration that attempts, in order:
 * <ol>
 *   <li>Start a Redis container via Testcontainers (requires Docker)</li>
 *   <li>Connect to a local Redis on {@code localhost:6379}</li>
 * </ol>
 * The winning host/port is exposed via {@link DynamicPropertySource} so
 * every {@code @SpringBootTest} in this module picks them up automatically.
 * <p>
 * The static initializer also sets the system property
 * {@code cacheguard.redis.available=true}. Tests gate themselves with
 * {@code @EnabledIf("com.cacheguard.TestRedisConfig#isRedisAvailable")}:
 * JUnit must invoke that method to decide whether to run them, which
 * forces this class to initialize (probe first, property set) <em>before</em>
 * the condition is evaluated. Relying on the system property alone would
 * be circular — Spring only loads this class after the condition passed.
 */
@Configuration
public class TestRedisConfig {

    static final String REDIS_AVAILABLE_PROPERTY = "cacheguard.redis.available";
    private static final String REDIS_HOST;
    private static final int REDIS_PORT;

    static {
        String host = null;
        int port = 6379;

        // 1. Try Testcontainers
        try {
            if (DockerClientFactory.instance().isDockerAvailable()) {
                GenericContainer<?> container =
                        new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                                .withExposedPorts(6379);
                container.start();
                host = container.getHost();
                port = container.getMappedPort(6379);
            }
        } catch (Throwable ignored) {
            // Docker not available
        }

        // 2. Fall back to localhost if reachable
        if (host == null && isPortReachable("localhost", 6379)) {
            host = "localhost";
            port = 6379;
        }

        if (host != null) {
            System.setProperty(REDIS_AVAILABLE_PROPERTY, "true");
        }
        REDIS_HOST = host;
        REDIS_PORT = port;
    }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", () -> REDIS_HOST);
        registry.add("spring.data.redis.port", () -> REDIS_PORT);
        registry.add(REDIS_AVAILABLE_PROPERTY, () -> REDIS_HOST != null);
    }

    /**
     * Whether the probe above discovered a usable Redis server.
     * Referenced by the tests' {@code @EnabledIf} condition; the act of
     * calling it triggers the static initializer, so the Docker/localhost
     * probe always runs before JUnit decides to execute or skip the tests.
     *
     * @return {@code true} when a Redis host was found
     */
    public static boolean isRedisAvailable() {
        return REDIS_HOST != null;
    }

    /** Quick TCP probe — returns {@code true} if the port accepts a connection. */
    private static boolean isPortReachable(String host, int port) {
        try (Socket socket = new Socket(host, port)) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
