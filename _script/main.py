#!/usr/bin/env python3
"""Host-side SMS worker: claim queued messages from the API, send via adb, report.

Worker id is read off the device (app SharedPreferences via `adb run-as`); set
env WORKER_ID only to pin it. Other config in _script/.env (env vars override):
PULLING_SERVER, ADMIN_TOKEN (required); PULL_INTERVAL (6.7), PULL_LIMIT (20),
PKG (com.smsgoku.app), ADB_SERIAL (optional).

    python main.py --start | --once | --check
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

ENV_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".env")
DEFAULT_PKG = "com.smsgoku.app"
DEFAULT_INTERVAL = 6.7
DEFAULT_PULL_LIMIT = 20
BROADCAST_FLAG = "0x00000020"  # FLAG_INCLUDE_STOPPED_PACKAGES
RESULT_RE = re.compile(r"result=(-?\d+)")
DATA_RE = re.compile(r'data="([^"]*)"')
PREFS_WORKER_RE = re.compile(r'name="worker_id"\s+value="(\d+)"')

_stop = False


def _handle_stop(signum, frame):  # noqa: ARG001
    global _stop
    _stop = True


def log(msg: str) -> None:
    print(f"{time.strftime('%Y-%m-%d %H:%M:%S')} {msg}", flush=True)


def load_env(path: str) -> dict:
    values: dict[str, str] = {}
    if not os.path.exists(path):
        return values
    with open(path, encoding="utf-8") as fh:
        for raw in fh:
            line = raw.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            if line.startswith("export "):
                line = line[len("export "):]
            key, _, val = line.partition("=")
            key, val = key.strip(), val.strip()
            if len(val) >= 2 and val[0] == val[-1] and val[0] in "\"'":
                val = val[1:-1]
            if key:
                values[key] = val
    return values


def device_worker_id(adb_base: list[str], package: str) -> int | None:
    prefs = f"/data/data/{package}/shared_prefs/admin_settings.xml"
    cmd = adb_base + ["shell", "run-as", package, "cat", prefs]
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=15)
    except (FileNotFoundError, subprocess.TimeoutExpired):
        return None
    if proc.returncode != 0:
        return None
    m = PREFS_WORKER_RE.search(proc.stdout or "")
    return int(m.group(1)) if m else None


class Config:
    def __init__(self, env: dict):
        def get(k: str, default: str | None = None) -> str | None:
            v = os.environ.get(k)
            if v is None:
                v = env.get(k)
            return v if v not in (None, "") else default

        self.api_base = (get("PULLING_SERVER") or "").rstrip("/")
        self.admin_token = get("ADMIN_TOKEN") or ""
        self.package = get("PKG", DEFAULT_PKG)
        self.adb_serial = get("ADB_SERIAL")
        self.adb_base = ["adb"] + (["-s", self.adb_serial] if self.adb_serial else [])

        try:
            self.interval = float(get("PULL_INTERVAL", str(DEFAULT_INTERVAL)))
        except ValueError:
            raise SystemExit(f"PULL_INTERVAL must be a number, got {get('PULL_INTERVAL')!r}")
        try:
            self.pull_limit = int(get("PULL_LIMIT", str(DEFAULT_PULL_LIMIT)))
        except ValueError:
            raise SystemExit(f"PULL_LIMIT must be an integer, got {get('PULL_LIMIT')!r}")

        missing = [k for k, v in (("PULLING_SERVER", self.api_base),
                                  ("ADMIN_TOKEN", self.admin_token)) if not v]
        if missing:
            raise SystemExit(f"missing required config in {ENV_PATH} (or env): "
                             + ", ".join(missing))

        worker_id = get("WORKER_ID")
        if worker_id not in (None, ""):
            try:
                self.worker_id = int(worker_id)
            except ValueError:
                raise SystemExit(f"WORKER_ID must be an integer, got {worker_id!r}")
            self.worker_id_source = "config"
        else:
            wid = device_worker_id(self.adb_base, self.package)
            if wid is None:
                raise SystemExit(
                    "WORKER_ID not set and none found on the device - register a "
                    f"worker in the app first, or set WORKER_ID env. "
                    f"({' '.join(self.adb_base)} run-as {self.package})")
            self.worker_id = wid
            self.worker_id_source = "device"

        if self.interval <= 0:
            raise SystemExit("PULL_INTERVAL must be > 0")


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


def list_workers(cfg: Config) -> list[dict]:
    status, payload = _request("GET", f"{cfg.api_base}/api/v1/admin/workers", cfg.admin_token)
    if status != 200:
        raise ApiError(f"workers -> {status}: {payload}")
    return payload or []


def pull_pending(cfg: Config) -> list[dict]:
    # GET /admin/sms/pending claims the rows it returns (status 0 -> 2).
    url = (f"{cfg.api_base}/api/v1/admin/sms/pending"
           f"?workerId={cfg.worker_id}&limit={cfg.pull_limit}")
    status, payload = _request("GET", url, cfg.admin_token)
    if status != 200:
        raise ApiError(f"pending -> {status}: {payload}")
    return payload or []


def report(cfg: Config, msg_id: int, error: str | None) -> None:
    url = f"{cfg.api_base}/api/v1/admin/sms/{msg_id}/report"
    status, payload = _request("PATCH", url, cfg.admin_token, {"error": error})
    if status in (200, 409):
        if status == 409:
            log(f"  msg {msg_id}: already reported (409), skipping")
        return
    raise ApiError(f"report {msg_id} -> {status}: {payload}")


def send_sms(cfg: Config, number: str, message: str) -> tuple[bool, str]:
    inner = (f"am broadcast -f {BROADCAST_FLAG} -n {cfg.package}/.SendSmsReceiver "
             f"--es number {shlex.quote(number)} --es message {shlex.quote(message)}")
    try:
        proc = subprocess.run(cfg.adb_base + ["shell", inner],
                              capture_output=True, text=True, timeout=30)
    except FileNotFoundError:
        return False, "adb not found on PATH"
    except subprocess.TimeoutExpired:
        return False, "adb broadcast timed out"

    out = (proc.stdout or "") + (proc.stderr or "")
    if proc.returncode != 0:
        return False, f"adb exit {proc.returncode}: {out.strip() or 'no output'}"
    m = RESULT_RE.search(out)
    d = DATA_RE.search(out)
    detail = d.group(1) if d else out.strip()
    if not m:
        return False, f"no result code in adb output: {out.strip()!r}"
    return m.group(1) == "-1", detail  # -1 == RESULT_OK


def process_once(cfg: Config) -> None:
    try:
        msgs = pull_pending(cfg)
    except ApiError as e:
        log(f"pull failed: {e}")
        return
    if not msgs:
        return
    log(f"claimed {len(msgs)} message(s)")
    for msg in msgs:
        mid, to, text = msg["id"], msg["to"], msg["message"]
        ok, detail = send_sms(cfg, to, text)
        log(f"  msg {mid} -> {to}: {'OK' if ok else 'FAIL'} ({detail})")
        try:
            report(cfg, mid, None if ok else detail)
        except ApiError as e:
            log(f"  msg {mid}: report failed: {e}")


def run_loop(cfg: Config) -> None:
    signal.signal(signal.SIGINT, _handle_stop)
    signal.signal(signal.SIGTERM, _handle_stop)
    log(f"polling {cfg.api_base} for worker {cfg.worker_id} (from {cfg.worker_id_source}) "
        f"every {cfg.interval}s (adb: {' '.join(cfg.adb_base)}, pkg: {cfg.package})")
    while not _stop:
        started = time.monotonic()
        process_once(cfg)
        sleep_for = cfg.interval - (time.monotonic() - started)
        while sleep_for > 0 and not _stop:
            time.sleep(min(sleep_for, 0.5))
            sleep_for -= 0.5
    log("stopped")


def run_check(cfg: Config) -> int:
    log(f"config OK - server {cfg.api_base}, worker {cfg.worker_id} "
        f"(from {cfg.worker_id_source}), interval {cfg.interval}s, pkg {cfg.package}")
    ok = True
    try:
        workers = list_workers(cfg)
        mine = next((w for w in workers if w.get("id") == cfg.worker_id), None)
        log(f"API reachable - {len(workers)} worker(s); worker {cfg.worker_id} "
            + (f"found: {mine.get('name')} ({mine.get('phone')})" if mine else "NOT in list"))
        ok = mine is not None
    except ApiError as e:
        log(f"API check FAILED: {e}")
        ok = False
    try:
        proc = subprocess.run(cfg.adb_base + ["get-state"],
                              capture_output=True, text=True, timeout=10)
        state = (proc.stdout or proc.stderr).strip()
        if proc.returncode == 0 and state == "device":
            log("adb device: connected")
        else:
            log(f"adb device check FAILED: {state or 'no device'}")
            ok = False
    except FileNotFoundError:
        log("adb device check FAILED: adb not found on PATH")
        ok = False
    except subprocess.TimeoutExpired:
        log("adb device check FAILED: timed out")
        ok = False
    return 0 if ok else 1


def main(argv: list[str]) -> int:
    p = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    mode = p.add_mutually_exclusive_group(required=True)
    mode.add_argument("--start", action="store_true", help="run the poll loop")
    mode.add_argument("--once", action="store_true", help="one poll cycle, then exit")
    mode.add_argument("--check", action="store_true", help="validate config + reachability")
    p.add_argument("--env", default=ENV_PATH, help=f"path to .env (default: {ENV_PATH})")
    args = p.parse_args(argv)

    cfg = Config(load_env(args.env))
    if args.check:
        return run_check(cfg)
    if args.once:
        process_once(cfg)
        return 0
    run_loop(cfg)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
