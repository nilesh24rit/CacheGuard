# CacheGuard

CacheGuard is a Redis-powered API gateway demo that protects login endpoints
from request floods and credential-stuffing attacks. It combines an atomic
per-IP sliding-window rate limiter with an anomaly-detection pipeline that
scores suspicious accounts into a global risk hotlist and gates future logins
according to that score.

## Overview

CacheGuard sits in front of a small simulated auth API and demonstrates two
defence layers working end to end:

- **Rate limiting** - every `/api` request is counted in Redis; a burst from a
  single IP is rejected with `429 Too Many Requests`.
- **Anomaly detection** - every login attempt is streamed into Redis, where a
  scheduled worker evaluates device-flood and known-credential signals, awards
  risk points, and feeds a hotlist that the login endpoint consults before it
  ever checks a password.

## Architecture

```
Request -> RateLimiterFilter -> Redis Sliding-Window (Lua)
                                      |
                                +-----+-----+
                                |           |
                              allow       429 (blocked)
                                |
                                v
                        POST /api/login events
                                |
                                v
                Redis Streams (login:stream:{username})
                                |
                                v
        Anomaly Worker (every 5s: HyperLogLog + Bloom filter)
                                |
                                v
                    Risk Hot-List (risk:hotlist ZSET)
                                |
                                v
                  Gateway Decision on next login:
       allow -> CAPTCHA challenge -> block (403)
```

### Walkthrough

1. **Request** - a client calls any `/api/...` endpoint, optionally declaring
   its source address with `X-Forwarded-For`.
2. **Filter** - `RateLimiterFilter` bumps the total-request counter and
   resolves the client IP (first `X-Forwarded-For` value, else remote address).
3. **Redis Sliding-Window** - a Lua script (`sliding_window.lua`) atomically
   prunes expired timestamps, adds the current request to a sorted set and
   returns the window count for `ratelimit:{ip}:{endpoint}`
   (200 requests per 60-second window per IP+endpoint).
4. **allow / 429** - inside the window the request proceeds to the controller;
   over the limit the filter answers `429` and bumps the blocked counter.
5. **Login events** - `POST /api/login` runs the risk gate first, then
   `LoginEventService.recordAttempt` records the attempt: `XADD` to the user's
   stream, `PFADD` the device id into the user's HyperLogLog, and `BF.ADD` the
   SHA-256 credential hash to a global Bloom filter (the `BF.ADD` reply itself
   yields the record-time *known credential* verdict that rides along in the
   stream event).
6. **Streams** - each active user has a `login:stream:{username}` stream; the
   worker drains it using a per-user cursor (`worker:lastid:{username}`), so
   every event is scored exactly once.
7. **Anomaly Worker (HLL + Bloom)** - every 5 seconds `StreamConsumerWorker`
   evaluates each new event through `AnomalyDetectionService`: a device count
   above the threshold awards +25 points (device flood) and a known-credential
   pattern awards +15 points (credential stuffing).
8. **Risk Hot-List** - points accumulate in the `risk:hotlist` sorted set,
   readable through `GET /api/hotlist` and the live dashboard.
9. **Gateway Decision** - the next login reads that score: below
   `allowScoreMax` credentials are checked normally, at or above
   `allowScoreMax` a CAPTCHA challenge is required, at or above
   `captchaScoreMax` the attempt is blocked outright with `403`.

## Redis Key Schema

| Key pattern | Type | Purpose | TTL |
|---|---|---|---|
| `ratelimit:{ip}:{endpoint}` | Sorted set | Sliding-window rate-limit counter: one member per request scored by timestamp; the Lua script prunes entries older than the window and returns the live count (limit 200 per 60 s) | 60 s, refreshed on every request |
| `login:stream:{username}` | Stream | Per-user login-attempt events (`ip`, `deviceId`, `result`, `timestamp`, `knownCredential`) written with `XADD`, drained by the anomaly worker | none (persistent) |
| `devices:hll:{username}` | HyperLogLog | Approximate set of distinct device ids per user; `PFCOUNT` above `cacheguard.risk.deviceCountThreshold` awards device-spike risk points | none (persistent) |
| `creds:bloom:attempts` | Bloom filter (RedisBloom) | SHA-256 hashes of every submitted `username:password` pair; the `BF.ADD` reply (0 = already present) produces the record-time known-credential verdict | none (persistent) |
| `risk:hotlist` | Sorted set | Global risk score per username (`ZINCRBY`); read by the login risk gate and by `GET /api/hotlist` | none (persistent) |
| `active:usernames` | Set | Users with recorded login activity; tells the worker which streams to scan | none (persistent) |
| `worker:lastid:{username}` | String | Per-user cursor: ID of the last stream entry that was scored, so each event contributes points exactly once | none (persistent) |
| `stats:requests:total`, `stats:requests:blocked`, `stats:logins:flagged` | String (counter) | Monotonic `INCR` counters served by `GET /api/stats` and the dashboard | none (persistent) |

## Tech stack

| Layer | Technology |
|---|---|
| Language / runtime | Java 25 |
| Framework | Spring Boot 3.5 (Web, Validation, Scheduling) |
| Data store | Redis Stack - streams, HyperLogLog, sorted sets, RedisBloom |
| Redis client | Spring Data Redis (Lettuce) |
| Rate-limit logic | Atomic Lua script executed via `DefaultRedisScript` |
| Build | Maven |
| Packaging | Docker / Docker Compose |
| Demo harness | Python 3 + `requests` (`scripts/load_test.py`) |
| Dashboard | Static `dashboard.html` + Chart.js served by Spring Boot |

## Setup

### Prerequisites

- Java 25
- Maven 3.6.3+
- Docker with Docker Compose (local Redis Stack)
- Python 3.9+ with the `requests` library (only for the demo scripts)

### Configuration

Redis connection settings live in `src/main/resources/application.yml`:

| Property | Default | Environment override |
|---|---|---|
| `spring.data.redis.host` | `localhost` | `REDIS_HOST` |
| `spring.data.redis.port` | `6379` | `REDIS_PORT` |
| `spring.data.redis.timeout` | `2000ms` | `REDIS_TIMEOUT` |
| `server.port` | `8080` | `SERVER_PORT` |

Risk-scoring thresholds live under `cacheguard.risk.*` - see the commented
`application.yml` for details.
