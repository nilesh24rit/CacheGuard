#!/usr/bin/env python3
"""CacheGuard demo load script.

Drives the CacheGuard API with synthetic traffic so the Redis-backed
rate limiter and the anomaly-detection pipeline can be demonstrated
end to end.

Modes:
    --normal    a few requests/sec from a handful of IPs to /api/health
    --attack    hundreds of requests/sec from one fixed IP to trigger 429s

Requirements:
    pip install requests
"""

from __future__ import annotations

import argparse
import sys
import time
from concurrent.futures import ThreadPoolExecutor

import requests

HEALTH_PATH = "/api/health"

# A handful of "client" IPs for normal traffic.
# TEST-NET-2 ranges (RFC 5737) are reserved for documentation/examples.
NORMAL_IPS = [
    "198.51.100.1",
    "198.51.100.2",
    "198.51.100.3",
    "198.51.100.4",
    "198.51.100.5",
]

# A single fixed "attacker" IP for the rate-limit attack.
# TEST-NET-3 range (RFC 5737), reserved for documentation/examples.
ATTACK_IP = "203.0.113.10"

# Mode-specific default request counts and delays (seconds between requests).
DEFAULTS = {
    "normal": (20, 0.3),
    "attack": (600, 0.002),
}


def summarize(status_counts: dict[int, int]) -> str:
    if not status_counts:
        return "no responses"
    return ", ".join(
        f"{code}: {status_counts[code]}" for code in sorted(status_counts)
    )


def send_get(
    session: requests.Session, url: str, ip: str, timeout: float = 5.0
) -> int | None:
    """Send a GET with a spoofed X-Forwarded-For header, return the status code."""
    try:
        resp = session.get(
            url, headers={"X-Forwarded-For": ip}, timeout=timeout
        )
    except requests.exceptions.RequestException as exc:
        print(f"[error] request to {url} failed: {exc}")
        return None
    return resp.status_code


def run_normal(base_url: str, count: int, delay: float) -> int:
    """A few requests/sec from a handful of IPs to /api/health.

    Nothing here should be rate-limited: the traffic stays well below
    the gateway limit, so every response should be 200.
    """
    print(f"[normal] sending {count} requests to {HEALTH_PATH}")
    print(f"[normal] rotating {len(NORMAL_IPS)} source IPs, delay={delay}s")

    status_counts: dict[int, int] = {}
    with requests.Session() as session:
        for i in range(count):
            ip = NORMAL_IPS[i % len(NORMAL_IPS)]
            status = send_get(session, base_url + HEALTH_PATH, ip)
            if status is None:
                print("[normal] aborting: is CacheGuard running? "
                      "Start it with: docker compose up --build")
                return 1
            status_counts[status] = status_counts.get(status, 0) + 1
            time.sleep(delay)

    print(f"[normal] done -> {summarize(status_counts)}")
    if set(status_counts) == {200}:
        print("[normal] OK: all responses were 200, no rate limiting triggered")
        return 0
    print("[normal] WARNING: unexpected status codes for normal traffic")
    return 1


def run_attack(base_url: str, count: int, delay: float, ip: str,
               workers: int) -> int:
    """Hammer /api/health from one fixed IP until the rate limiter kicks in.

    The gateway allows 200 requests per 60s window per IP+endpoint, so a
    burst of several hundred requests from a single IP should produce a
    mix of 200 and 429 responses. Requests are spread over several
    threads so the attack genuinely reaches hundreds of req/s.
    Prints the 200 vs 429 summary.
    """
    print(f"[attack] sending {count} requests to {HEALTH_PATH} from fixed IP {ip}")
    print(f"[attack] {workers} workers, delay={delay}s per request")

    # Round-robin partition of request indices; each worker keeps its own
    # Session (connection pooling) so requests overlap in flight.
    chunks: list[list[int]] = [[] for _ in range(workers)]
    for i in range(count):
        chunks[i % workers].append(i)

    def worker_run(indices: list[int]) -> list[int | None]:
        statuses: list[int | None] = []
        with requests.Session() as session:
            for _ in indices:
                status = send_get(session, base_url + HEALTH_PATH, ip)
                statuses.append(status)
                if status is None:  # connection error -> stop this worker
                    break
                if delay > 0:
                    time.sleep(delay)
        return statuses

    started = time.perf_counter()
    with ThreadPoolExecutor(max_workers=workers) as pool:
        results = list(pool.map(worker_run, chunks))
    elapsed = time.perf_counter() - started

    statuses_flat = [s for chunk in results for s in chunk]
    if None in statuses_flat:
        print("[attack] aborting: is CacheGuard running? "
              "Start it with: docker compose up --build")
        return 1

    status_counts: dict[int, int] = {}
    for status in statuses_flat:
        status_counts[status] = status_counts.get(status, 0) + 1

    sent = len(statuses_flat)
    ok = status_counts.get(200, 0)
    rate_limited = status_counts.get(429, 0)
    other = sent - ok - rate_limited

    print(f"[attack] finished {sent} requests in {elapsed:.1f}s "
          f"(~{sent / elapsed:.0f} req/s)")
    print(f"[attack] 200 OK:           {ok}")
    print(f"[attack] 429 rate-limited: {rate_limited}")
    if other:
        print(f"[attack] other:           {other} -> {summarize(status_counts)}")

    if rate_limited > 0:
        print(f"[attack] OK: rate limiting is working: "
              f"{rate_limited} of {sent} requests were blocked with 429")
        return 0
    print("[attack] WARNING: no 429s seen - the limit is 200 requests per "
          "60s per IP+endpoint; raise --count above that")
    return 1


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="CacheGuard load-test / demo script.",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )

    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument(
        "--normal",
        action="store_true",
        help="simulate benign traffic: a few requests/sec from a handful of IPs to /api/health",
    )
    mode.add_argument(
        "--attack",
        action="store_true",
        help="simulate a rate-limit attack: hundreds of requests/sec from one fixed IP",
    )
    parser.add_argument(
        "--ip",
        default=ATTACK_IP,
        help="fixed source IP used by --attack (rate-limit key; wait 60s or change it to reset the window)",
    )
    parser.add_argument(
        "--workers",
        type=int,
        default=8,
        help="concurrent request threads used by --attack",
    )

    parser.add_argument(
        "--url",
        default="http://localhost:8080",
        help="base URL of the running CacheGuard app",
    )
    parser.add_argument(
        "--count",
        type=int,
        default=None,
        help="number of requests to send (mode-specific default if omitted)",
    )
    parser.add_argument(
        "--delay",
        type=float,
        default=None,
        help="delay between requests in seconds (mode-specific default if omitted)",
    )

    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)

    if args.normal:
        count, delay = DEFAULTS["normal"]
        if args.count is not None:
            count = args.count
        if args.delay is not None:
            delay = args.delay
        if count < 1:
            print("[error] --count must be >= 1")
            return 2
        if delay < 0:
            print("[error] --delay must be >= 0")
            return 2
        return run_normal(args.url.rstrip("/"), count, delay)

    if args.attack:
        count, delay = DEFAULTS["attack"]
        if args.count is not None:
            count = args.count
        if args.delay is not None:
            delay = args.delay
        if count < 1:
            print("[error] --count must be >= 1")
            return 2
        if delay < 0:
            print("[error] --delay must be >= 0")
            return 2
        if args.workers < 1:
            print("[error] --workers must be >= 1")
            return 2
        return run_attack(args.url.rstrip("/"), count, delay, args.ip,
                          args.workers)

    print("[error] no mode selected")
    return 2


if __name__ == "__main__":
    sys.exit(main())
