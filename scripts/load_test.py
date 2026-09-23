#!/usr/bin/env python3
"""CacheGuard demo load script.

Drives the CacheGuard API with synthetic traffic so the Redis-backed
rate limiter and the anomaly-detection pipeline can be demonstrated
end to end.

Modes:
    --normal    a few requests/sec from a handful of IPs to /api/health

Requirements:
    pip install requests
"""

from __future__ import annotations

import argparse
import sys
import time

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

# Mode-specific default request counts and delays (seconds between requests).
DEFAULTS = {
    "normal": (20, 0.3),
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

    print("[error] no mode selected")
    return 2


if __name__ == "__main__":
    sys.exit(main())
