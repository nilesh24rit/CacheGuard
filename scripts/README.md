# CacheGuard demo scripts

`load_test.py` drives the running CacheGuard service with synthetic traffic so
you can prove, end to end, that:

1. the Redis sliding-window **rate limiter** rejects bursts with `429`, and
2. the **anomaly detection** pipeline (login stream -> HyperLogLog/Bloom filter
   -> worker -> risk hotlist) flags credential-stuffing attempts.

## Prerequisites

- Docker with Docker Compose (runs Redis Stack + the app)
- Python 3.9+ with the `requests` library:

```bash
python -m pip install requests     # Windows: py -3 -m pip install requests
```

## 1. Start the app

From the repository root:

```bash
docker compose up --build
```

This starts:

- `redis` - Redis Stack on `6379` (RedisInsight on `8001`)
- `app` - the Spring Boot service on `8080`

Wait for the container to come up, then verify:

```bash
curl http://localhost:8080/api/health
# OK
```

> **Clean slate:** Redis data lives inside the container, so
> `docker compose down && docker compose up -d` gives you an empty database.
> The rate-limit window also resets itself after 60 seconds without traffic.

All script commands below assume the default URL `http://localhost:8080`
(override with `--url`).

## 2. Normal traffic (`--normal`)

A few requests per second, rotating across five source IPs, against
`/api/health`. Nothing here should ever be rate-limited.

```bash
python scripts/load_test.py --normal
```

Defaults: 20 requests, 0.3 s apart (about 3 requests/sec), IPs
`198.51.100.1`-`198.51.100.5`.

**Successful output:**

```
[normal] sending 20 requests to /api/health
[normal] rotating 5 source IPs, delay=0.3s
[normal] done -> 200: 20
[normal] OK: all responses were 200, no rate limiting triggered
```

Exit code `0`.

## 3. Rate-limit attack (`--attack`)

Hundreds of requests per second from **one fixed IP** (`203.0.113.10`) at
`/api/health`. The gateway allows 200 requests per 60-second window per
IP+endpoint, so the burst gets split into `200` vs `429` responses, which the
script counts for you.

```bash
python scripts/load_test.py --attack
```

Defaults: 600 requests, 8 worker threads, 0.002 s delay per request.

**Successful output (fresh window):**

```
[attack] sending 600 requests to /api/health from fixed IP 203.0.113.10
[attack] 8 workers, delay=0.002s per request
[attack] finished 600 requests in 3.1s (~197 req/s)
[attack] 200 OK:           200
[attack] 429 rate-limited: 400
[attack] OK: rate limiting is working: 400 of 600 requests were blocked with 429
```

Exit code `0` as long as at least one `429` was seen.

Notes:

- The first 200 requests in a fresh window pass; everything else is blocked.
  Exact counts vary with timing.
- The window key is `ratelimit:<ip>:<endpoint>`. Re-running within 60 seconds
  starts against an already-exhausted window (expect all `429`s). Wait a
  minute or pick a fresh key: `--ip 203.0.113.11`.
- You can also watch `blockedCount` climb in `GET /api/stats` or in the
  dashboard at `http://localhost:8080/dashboard.html`.

## 4. Credential stuffing (`--stuffing`)

One username, one guessed password, sprayed at `POST /api/login` from **many
unique fake deviceIds** while source IPs rotate through `192.0.2.0/24`, so the
per-IP rate limiter never fires. Detection therefore has to come from the
anomaly pipeline: the per-user device HyperLogLog exceeds the device threshold
(3) and the credential is retried, so the worker awards risk points and the
account lands on the hotlist. The script then calls `GET /api/hotlist`
**itself** and prints the top flagged usernames to prove it.

```bash
python scripts/load_test.py --stuffing
```

Defaults: 40 attempts, 0.05 s apart, target user `alice`, guessed password
`Spring2026!`, then waits 7 s for the worker (it polls every 5 s) and fetches
the top 10 hotlist entries.

**Successful output:**

```
[stuffing] sending 40 login attempts for user 'alice'
[stuffing] one password, 40 unique fake deviceIds, rotating fake IPs, delay=0.05s
[stuffing] done -> 200: 40
[stuffing] outcomes: invalid: 40
[stuffing] the anomaly worker scans login events every 5s; GET /api/hotlist shows the flagged usernames
[stuffing] waiting 7s for the anomaly worker (it polls every 5s)...
[stuffing] GET /api/hotlist?top=10 -> 1 entries (top flagged usernames)
  #1 alice                score=500.0  <-- stuffed account
[stuffing] OK: 'alice' is on the hotlist - anomaly detection flagged the stuffing attempt
```

Exit code `0` only when the targeted username appears on the hotlist.

Notes:

- Exact scores vary (they depend on how many attempts were recorded before a
  worker tick); what matters is that the account is flagged with a non-zero
  score.
- Depending on when the 5-second worker tick lands, later attempts in the run
  may already come back as `CAPTCHA verification required` or `403 Account
  locked` - that is the risk gate reacting to the climbing score, which is
  part of the demo.
- The app also logs each detection, e.g.
  `[StreamWorker] user=alice device spike detected (40 > 3), +25 risk pts`.
- `GET /api/events/alice` shows the raw login stream if you want to inspect
  the recorded deviceIds.

## 5. Common options

| Option        | Default               | Meaning                                              |
|---------------|-----------------------|------------------------------------------------------|
| `--url`       | `http://localhost:8080` | Base URL of the app                                |
| `--count`     | mode-specific         | Number of requests                                   |
| `--delay`     | mode-specific         | Delay between requests, seconds                      |
| `--ip`        | `203.0.113.10`        | Fixed attacker IP for `--attack`                     |
| `--workers`   | `8`                   | Concurrent threads for `--attack`                    |
| `--username`  | `alice`               | Target account for `--stuffing`                      |
| `--password`  | `Spring2026!`         | Guessed password for `--stuffing`                    |
| `--wait`      | `7`                   | Seconds to wait before the hotlist query             |
| `--top`       | `10`                  | Hotlist entries to fetch                             |

Each mode runs independently; exactly one of `--normal`, `--attack`,
`--stuffing` must be given. Run `python scripts/load_test.py --help` for the
full list.

## Troubleshooting

- **Connection refused / "is CacheGuard running?"** - the app is not up yet;
  check `docker compose ps` and the `app` container logs.
- **`--attack` shows no 429s** - the window is 200 requests per 60 s; raise
  `--count` above 200.
- **Hotlist empty after `--stuffing`** - the worker needs a tick to run;
  increase `--wait` (it polls every 5 s) or `--count`, and make sure Redis is
  reachable (`docker compose ps`, app logs).
- **Results look "sticky" between runs** - Redis keeps state (risk scores,
  rate-limit windows, device counts). Reset with
  `docker compose exec redis redis-cli FLUSHALL`.
