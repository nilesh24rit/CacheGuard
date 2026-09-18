# CacheGuard

CacheGuard is a Maven Spring Boot 3.x service (Java 25) that uses Redis Stack for caching and related Redis-backed workflows.

## Requirements

- Java 25
- Maven 3.6.3+
- Docker and Docker Compose (for local Redis Stack)

## Configuration

Redis connection settings live in `src/main/resources/application.yml`:

- `spring.data.redis.host` (default `localhost`)
- `spring.data.redis.port` (default `6379`)
- `spring.data.redis.timeout` (default `2000ms`)

Override them with `REDIS_HOST`, `REDIS_PORT`, and `REDIS_TIMEOUT`.

## Run with Docker Compose

```bash
docker compose up --build
```

This starts:

- `redis` — `redis/redis-stack` on port `6379` (RedisInsight on `8001`)
- `app` — the Spring Boot application on port `8080`, connected to Redis

## Run locally

Start Redis Stack, then:

```bash
mvn spring-boot:run
```

## Health check

`GET /api/health` sends a Redis `PING` and returns `OK` when the connection works.
