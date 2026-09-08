#!/usr/bin/env python3
"""Poll the admin API for queued SMS and send them through the mobileui Android app.

Acts as one worker device: every POLL_INTERVAL seconds it claims pending messages
for WORKER_ID via `GET /api/v1/admin/sms/pending`, sends each one by broadcasting
to `com.smsjustu.app/.SendSmsReceiver` over adb, then closes the loop with
`PATCH /api/v1/admin/sms/:id/report`.

The receiver is the mobileui app's SendSmsReceiver (see
mobileui/app/src/main/java/com/smsjustu/app/SendSmsReceiver.kt) - install that
app and grant it SEND_SMS, no other setup or the sync loop needed.

Config comes from CLI flags or the matching env vars:

    API_BASE       base URL of the API            (--api-base)
    ADMIN_TOKEN    X-Admin-Token shared secret    (--admin-token)
    WORKER_ID      worker_tokens.id to pull for   (--worker-id)
    POLL_INTERVAL  seconds between polls, def 6.7  (--interval)
    ADB_SERIAL     target a specific adb device   (--adb-serial)
    PKG            app package                     (--package)

Stdlib only. Ctrl-C to stop.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shlex
import signal
import subprocess
import sys
import time
import urllib.error
import urllib.request

DEFAULT_PKG = "com.smsjustu.app"
DEFAULT_INTERVAL = 6.7
PULL_LIMIT = 20
# FLAG_INCLUDE_STOPPED_PACKAGES — needed for the first broadcast after the app is
# installed or the phone rebooted, harmless every other time.
BROADCAST_FLAG = "0x00000020"
RESULT_RE = re.compile(r"result=(-?\d+)")
DATA_RE = re.compile(r'data="([^"]*)"')

_stop = False


def _handle_sigterm(signum, frame):  # noqa: ARG001
    global _stop
    _stop = True


def log(msg: str) -> None:
    print(f"{time.strftime('%Y-%m-%d %H:%M:%S')} {msg}", flush=True)


# --- API -------------------------------------------------------------------

class ApiError(RuntimeError):
    pass


def _request(method: str, url: str, token: str, body: dict | None = None) -> tuple[int, object]:
    data = None
    headers = {"X-Admin-Token": token}
    if body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            raw = resp.read()
            return resp.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            payload = json.loads(raw) if raw else None
        except ValueError:
            payload = raw.decode(errors="replace")
        return e.code, payload
    except urllib.error.URLError as e:
        raise ApiError(f"{method} {url}: {e.reason}") from e


def pull_pending(api_base: str, token: str, worker_id: int) -> list[dict]:
    url = f"{api_base}/api/v1/admin/sms/pending?workerId={worker_id}&limit={PULL_LIMIT}"
    status, payload = _request("GET", url, token)
    if status != 200:
        raise ApiError(f"pending -> {status}: {payload}")
    return payload or []


def report(api_base: str, token: str, msg_id: int, error: str | None) -> None:
    url = f"{api_base}/api/v1/admin/sms/{msg_id}/report"
    status, payload = _request("PATCH", url, token, {"error": error})
    if status == 200:
        return
    if status == 409:
        log(f"  msg {msg_id}: already reported (409), skipping")
        return
    raise ApiError(f"report {msg_id} -> {status}: {payload}")


# --- SMS via adb ---------------------------------------------------------------

def send_sms(adb_base: list[str], pkg: str, number: str, message: str) -> tuple[bool, str]:
    """Broadcast one SMS to the app's SendSmsReceiver. Returns (ok, detail)."""
    inner = (
        f"am broadcast -f {BROADCAST_FLAG} "
        f"-n {pkg}/.SendSmsReceiver "
        f"--es number {shlex.quote(number)} "
        f"--es message {shlex.quote(message)}"
    )
    cmd = adb_base + ["shell", inner]
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=30)
    except FileNotFoundError:
        return False, "adb not found on PATH"
    except subprocess.TimeoutExpired:
        return False, "adb broadcast timed out"

    out = (proc.stdout or "") + (proc.stderr or "")
    if proc.returncode != 0:
        return False, f"adb exit {proc.returncode}: {out.strip() or 'no output'}"

    m = RESULT_RE.search(out)
    d = DATA_RE.search(out)
    detail = (d.group(1) if d else out.strip())
    if not m:
        return False, f"no result code in adb output: {out.strip()!r}"
    # -1 == Activity.RESULT_OK set by the receiver; 0 == RESULT_CANCELED / failure.
    return (m.group(1) == "-1"), detail


# --- main loop ---------------------------------------------------------------

def process_once(cfg) -> None:
    try:
        msgs = pull_pending(cfg.api_base, cfg.admin_token, cfg.worker_id)
    except ApiError as e:
        log(f"pull failed: {e}")
        return

    if not msgs:
        return

    log(f"claimed {len(msgs)} message(s)")
    for msg in msgs:
        mid, to, text = msg["id"], msg["to"], msg["message"]
        ok, detail = send_sms(cfg.adb_base, cfg.package, to, text)
        log(f"  msg {mid} -> {to}: {'OK' if ok else 'FAIL'} ({detail})")
        try:
            report(cfg.api_base, cfg.admin_token, mid, None if ok else detail)
        except ApiError as e:
            log(f"  msg {mid}: report failed: {e}")


def build_config(argv: list[str]):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--api-base", default=os.environ.get("API_BASE"))
    p.add_argument("--admin-token", default=os.environ.get("ADMIN_TOKEN"))
    p.add_argument("--worker-id", type=int, default=os.environ.get("WORKER_ID"))
    p.add_argument("--interval", type=float,
                   default=float(os.environ.get("POLL_INTERVAL", DEFAULT_INTERVAL)))
    p.add_argument("--adb-serial", default=os.environ.get("ADB_SERIAL"))
    p.add_argument("--package", default=os.environ.get("PKG", DEFAULT_PKG))
    cfg = p.parse_args(argv)

    missing = [n for n in ("api_base", "admin_token", "worker_id") if getattr(cfg, n) in (None, "")]
    if missing:
        p.error("missing required config: " + ", ".join(m.upper() for m in missing))

    cfg.api_base = cfg.api_base.rstrip("/")
    cfg.worker_id = int(cfg.worker_id)
    cfg.adb_base = ["adb"] + (["-s", cfg.adb_serial] if cfg.adb_serial else [])
    return cfg


def main(argv: list[str]) -> int:
    cfg = build_config(argv)
    signal.signal(signal.SIGINT, _handle_sigterm)
    signal.signal(signal.SIGTERM, _handle_sigterm)

    log(f"polling {cfg.api_base} for worker {cfg.worker_id} every {cfg.interval}s "
        f"(adb: {' '.join(cfg.adb_base)}, pkg: {cfg.package})")

    while not _stop:
        started = time.monotonic()
        process_once(cfg)
        # keep a steady cadence regardless of how long the cycle took
        sleep_for = cfg.interval - (time.monotonic() - started)
        while sleep_for > 0 and not _stop:
            time.sleep(min(sleep_for, 0.5))
            sleep_for -= 0.5

    log("stopped")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
