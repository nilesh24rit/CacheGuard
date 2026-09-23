#!/usr/bin/env python3
"""CacheGuard demo load script.

Drives the CacheGuard API with synthetic traffic so the Redis-backed
rate limiter and the anomaly-detection pipeline can be demonstrated
end to end.

Modes:
    --normal    a few requests/sec from a handful of IPs to /api/health
    --attack    hundreds of requests/sec from one fixed IP to trigger 429s
    --stuffing  one username hit from many fake deviceIds via /api/login

Requirements:
    pip install requests
"""

from __future__ import annotations

import argparse
import sys
import time
import uuid
from concurrent.futures import ThreadPoolExecutor

import requests

HEALTH_PATH = "/api/health"
LOGIN_PATH = "/api/login"
HOTLIST_PATH = "/api/hotlist"

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

# Default stuffing target: one account, one (wrong) guessed password.
STUFF_USERNAME = "alice"
STUFF_PASSWORD = "Spring2026!"  # attacker's guess, not a real password

# Mode-specific default request counts and delays (seconds between requests).
DEFAULTS = {
    "normal": (20, 0.3),
    "attack": (600, 0.002),
    "stuffing": (40, 0.05),
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


def run_stuffing(base_url: str, count: int, delay: float,
                 username: str, password: str) -> int:
    """Simulate credential stuffing against POST /api/login.

    One username and one guessed password are sprayed from many unique
    fake deviceIds. Source IPs rotate through the TEST-NET-1 range so the
    per-IP rate limiter never fires - detection has to come from the
    per-user device anomaly (HyperLogLog) maintained by the app.
    """
    print(f"[stuffing] sending {count} login attempts for user '{username}'")
    print(f"[stuffing] one password, {count} unique fake deviceIds, "
          f"rotating fake IPs, delay={delay}s")

    outcomes = {"invalid": 0, "captcha": 0, "locked": 0, "success": 0,
                "rate-limited": 0, "other": 0}
    status_counts: dict[int, int] = {}

    with requests.Session() as session:
        for i in range(count):
            ip = f"192.0.2.{(i % 250) + 1}"
            payload = {
                "username": username,
                "password": password,
                "ip": ip,
                "deviceId": f"device-{uuid.uuid4().hex[:16]}",
            }
            try:
                resp = session.post(
                    base_url + LOGIN_PATH,
                    json=payload,
                    headers={"X-Forwarded-For": ip},
                    timeout=5,
                )
            except requests.exceptions.RequestException as exc:
                print(f"[stuffing] request failed: {exc}")
                print("[stuffing] aborting: is CacheGuard running? "
                      "Start it with: docker compose up --build")
                return 1
            status_counts[resp.status_code] = \
                status_counts.get(resp.status_code, 0) + 1

            if resp.status_code == 403:
                outcomes["locked"] += 1
            elif resp.status_code == 429:
                outcomes["rate-limited"] += 1
            elif resp.status_code == 200:
                try:
                    body = resp.json()
                except ValueError:
                    outcomes["other"] += 1
                else:
                    if body.get("requiresCaptcha"):
                        outcomes["captcha"] += 1
                    elif body.get("success"):
                        outcomes["success"] += 1
                    else:
                        outcomes["invalid"] += 1
            else:
                outcomes["other"] += 1
            time.sleep(delay)

    print(f"[stuffing] done -> {summarize(status_counts)}")
    print("[stuffing] outcomes: "
          + ", ".join(f"{k}: {v}" for k, v in outcomes.items() if v))
    if outcomes["captcha"] or outcomes["locked"]:
        print("[stuffing] the risk gate kicked in mid-attack "
              "(CAPTCHA/lock) while the risk score was climbing")
    print("[stuffing] the anomaly worker scans login events every 5s; "
          "GET /api/hotlist shows the flagged usernames")
    return 0


def check_hotlist(base_url: str, username: str, top: int, wait: float) -> int:
    """Query GET /api/hotlist and print the top flagged usernames.

    Proves detection worked end to end: after the stuffing run the
    anomaly worker scores the targeted account, so it must show up on
    the global risk hotlist.
    """
    print(f"[stuffing] waiting {wait:g}s for the anomaly worker "
          "(it polls every 5s)...")
    time.sleep(wait)

    try:
        resp = requests.get(base_url + HOTLIST_PATH,
                            params={"top": top}, timeout=5)
    except requests.exceptions.RequestException as exc:
        print(f"[stuffing] hotlist request failed: {exc}")
        return 1
    if resp.status_code != 200:
        print(f"[stuffing] GET {HOTLIST_PATH} returned {resp.status_code}")
        return 1
    try:
        data = resp.json()
    except ValueError:
        print(f"[stuffing] GET {HOTLIST_PATH} did not return JSON")
        return 1

    entries = data.get("entries", [])
    print(f"[stuffing] GET {HOTLIST_PATH}?top={top} -> {len(entries)} "
          "entries (top flagged usernames)")
    if not entries:
        print("[stuffing] hotlist is empty - detection flagged nobody; "
              "try a larger --count")
        return 1
    for i, entry in enumerate(entries, 1):
        marker = ("  <-- stuffed account"
                  if entry.get("username") == username else "")
        print(f"  #{i} {str(entry.get('username', '?')):<20} "
              f"score={entry.get('score')}{marker}")

    if any(entry.get("username") == username for entry in entries):
        print(f"[stuffing] OK: '{username}' is on the hotlist - "
              "anomaly detection flagged the stuffing attempt")
        return 0
    print(f"[stuffing] WARNING: '{username}' is not on the hotlist yet - "
          "try a larger --count or a longer --wait")
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
    mode.add_argument(
        "--stuffing",
        action="store_true",
        help="simulate credential stuffing: one username/password sprayed from many fake deviceIds via /api/login",
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
        "--username",
        default=STUFF_USERNAME,
        help="target account for --stuffing",
    )
    parser.add_argument(
        "--password",
        default=STUFF_PASSWORD,
        help="guessed password for --stuffing",
    )
    parser.add_argument(
        "--wait",
        type=float,
        default=7.0,
        help="seconds to wait after --stuffing before querying /api/hotlist (worker ticks every 5s)",
    )
    parser.add_argument(
        "--top",
        type=int,
        default=10,
        help="number of hotlist entries to fetch after --stuffing",
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
        mode = "normal"
    elif args.attack:
        mode = "attack"
    elif args.stuffing:
        mode = "stuffing"
    else:
        print("[error] no mode selected")
        return 2

    count, delay = DEFAULTS[mode]
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

    base_url = args.url.rstrip("/")

    if mode == "normal":
        return run_normal(base_url, count, delay)

    if mode == "attack":
        if args.workers < 1:
            print("[error] --workers must be >= 1")
            return 2
        return run_attack(base_url, count, delay, args.ip, args.workers)

    rc = run_stuffing(base_url, count, delay, args.username, args.password)
    if rc != 0:
        return rc

    # Prove detection: fetch the hotlist ourselves and flag our target.
    if args.wait < 0:
        print("[error] --wait must be >= 0")
        return 2
    if args.top < 1:
        print("[error] --top must be >= 1")
        return 2
    return check_hotlist(base_url, args.username, args.top, args.wait)


if __name__ == "__main__":
    sys.exit(main())
