#!/usr/bin/env python3
"""Host-side SMS worker: claim queued messages from the API, send via adb, report.

All config comes from _script/.env (or matching environment variables, which
win). Nothing is hardcoded. The worker id is not configured - each poll it is
read live off the device (the app's registered worker, via `adb run-as`).

    python main.py --start | --check
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

# Config keys read from .env / env. Required must be non-empty; optional may be
# absent or blank. No defaults live here - .env is the single source.
REQUIRED = (
    "PULLING_SERVER", "ADMIN_TOKEN", "API_PREFIX", "PKG", "PREFS_FILE",
    "ADB_BIN", "BROADCAST_FLAG", "PULL_INTERVAL", "PULL_LIMIT",
    "HTTP_TIMEOUT", "ADB_TIMEOUT", "SLEEP_GRANULARITY",
)
OPTIONAL = ("ADB_SERIAL",)

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


def device_worker_id(cfg: "Config") -> int | None:
    prefs = f"/data/data/{cfg.pkg}/shared_prefs/{cfg.prefs_file}"
    cmd = cfg.adb_base + ["shell", "run-as", cfg.pkg, "cat", prefs]
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=cfg.adb_timeout)
    except (FileNotFoundError, subprocess.TimeoutExpired):
        return None
    if proc.returncode != 0:
        return None
    m = PREFS_WORKER_RE.search(proc.stdout or "")
    return int(m.group(1)) if m else None


class Config:
    def __init__(self, env: dict, env_path: str = ENV_PATH):
        merged = {k: os.environ.get(k, env.get(k, "")).strip()
                  for k in REQUIRED + OPTIONAL}

        missing = [k for k in REQUIRED if not merged[k]]
        if missing:
            raise SystemExit(f"missing required config in {env_path} (or env): "
                             + ", ".join(missing))

        def num(key, cast):
            try:
                return cast(merged[key])
            except ValueError:
                raise SystemExit(f"{key} must be {cast.__name__}, got {merged[key]!r}")

        self.api_base = merged["PULLING_SERVER"].rstrip("/")
        self.api_prefix = "/" + merged["API_PREFIX"].strip("/")
        self.admin_token = merged["ADMIN_TOKEN"]
        self.pkg = merged["PKG"]
        self.prefs_file = merged["PREFS_FILE"]
        self.broadcast_flag = merged["BROADCAST_FLAG"]
        self.interval = num("PULL_INTERVAL", float)
        self.pull_limit = num("PULL_LIMIT", int)
        self.http_timeout = num("HTTP_TIMEOUT", float)
        self.adb_timeout = num("ADB_TIMEOUT", float)
        self.sleep_granularity = num("SLEEP_GRANULARITY", float)
        self.adb_base = [merged["ADB_BIN"]] + (
            ["-s", merged["ADB_SERIAL"]] if merged["ADB_SERIAL"] else [])

        if self.interval <= 0:
            raise SystemExit("PULL_INTERVAL must be > 0")

    def url(self, path: str) -> str:
        return f"{self.api_base}{self.api_prefix}{path}"


class ApiError(RuntimeError):
    pass


def _request(cfg: Config, method: str, url: str, body: dict | None = None) -> tuple[int, object]:
    data = None
    headers = {"X-Admin-Token": cfg.admin_token}
    if body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=cfg.http_timeout) as resp:
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
    status, payload = _request(cfg, "GET", cfg.url("/admin/workers"))
    if status != 200:
        raise ApiError(f"workers -> {status}: {payload}")
    return payload or []


def pull_pending(cfg: Config, worker_id: int) -> list[dict]:
    # GET /admin/sms/pending claims the rows it returns (status 0 -> 2).
    url = cfg.url(f"/admin/sms/pending?workerId={worker_id}&limit={cfg.pull_limit}")
    status, payload = _request(cfg, "GET", url)
    if status != 200:
        raise ApiError(f"pending -> {status}: {payload}")
    return payload or []


def report(cfg: Config, msg_id: int, error: str | None) -> None:
    status, payload = _request(cfg, "PATCH", cfg.url(f"/admin/sms/{msg_id}/report"),
                               {"error": error})
    if status in (200, 409):
        if status == 409:
            log(f"  msg {msg_id}: already reported (409), skipping")
        return
    raise ApiError(f"report {msg_id} -> {status}: {payload}")


def send_sms(cfg: Config, number: str, message: str) -> tuple[bool, str]:
    inner = (f"am broadcast -f {cfg.broadcast_flag} -n {cfg.pkg}/.SendSmsReceiver "
             f"--es number {shlex.quote(number)} --es message {shlex.quote(message)}")
    try:
        proc = subprocess.run(cfg.adb_base + ["shell", inner],
                              capture_output=True, text=True, timeout=cfg.adb_timeout)
    except FileNotFoundError:
        return False, f"{cfg.adb_base[0]} not found on PATH"
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
    worker_id = device_worker_id(cfg)
    if worker_id is None:
        log("no worker registered on the device - register one in the app; skipping")
        return
    try:
        msgs = pull_pending(cfg, worker_id)
    except ApiError as e:
        log(f"pull failed: {e}")
        return
    if not msgs:
        return
    log(f"worker {worker_id}: claimed {len(msgs)} message(s)")
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
    log(f"polling {cfg.api_base} every {cfg.interval}s "
        f"(adb: {' '.join(cfg.adb_base)}, pkg: {cfg.pkg}) - worker id read from device each cycle")
    while not _stop:
        started = time.monotonic()
        process_once(cfg)
        sleep_for = cfg.interval - (time.monotonic() - started)
        while sleep_for > 0 and not _stop:
            time.sleep(min(sleep_for, cfg.sleep_granularity))
            sleep_for -= cfg.sleep_granularity
    log("stopped")


def run_check(cfg: Config) -> int:
    log(f"config OK - server {cfg.api_base}, interval {cfg.interval}s, pkg {cfg.pkg}")
    ok = True
    worker_id = device_worker_id(cfg)
    if worker_id is None:
        log("device worker: NONE - register a worker in the app")
        ok = False
    else:
        log(f"device worker: {worker_id}")
    try:
        workers = list_workers(cfg)
        mine = next((w for w in workers if w.get("id") == worker_id), None)
        log(f"API reachable - {len(workers)} worker(s); "
            + (f"worker {worker_id} found: {mine.get('name')} ({mine.get('phone')})"
               if mine else f"worker {worker_id} NOT in list"))
        if worker_id is not None and mine is None:
            ok = False
    except ApiError as e:
        log(f"API check FAILED: {e}")
        ok = False
    try:
        proc = subprocess.run(cfg.adb_base + ["get-state"],
                              capture_output=True, text=True, timeout=cfg.adb_timeout)
        state = (proc.stdout or proc.stderr).strip()
        if proc.returncode == 0 and state == "device":
            log("adb device: connected")
        else:
            log(f"adb device check FAILED: {state or 'no device'}")
            ok = False
    except FileNotFoundError:
        log(f"adb device check FAILED: {cfg.adb_base[0]} not found on PATH")
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
    mode.add_argument("--check", action="store_true", help="validate config + reachability")
    p.add_argument("--env", default=ENV_PATH, help=f"path to .env (default: {ENV_PATH})")
    args = p.parse_args(argv)

    cfg = Config(load_env(args.env), args.env)
    if args.check:
        return run_check(cfg)
    run_loop(cfg)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
