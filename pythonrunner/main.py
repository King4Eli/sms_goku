#!/usr/bin/env python3
"""Local runner: claim from the API, send via USB phone (adb), report back.

Config: env vars (see runner.env.example). Stdlib only.

    python3 pythonrunner/main.py
"""

import base64
import json
import logging
import os
import re
import shlex
import signal
import subprocess
import time
import urllib.error
import urllib.request

GATEWAY_PKG = "com.smsgoku.gateway"  # = mobile app id
RESULT_RE = re.compile(r"Result: Bundle\[\{r=(OK|ERR) (.*)\}\]")

log = logging.getLogger("runner")
_stop = False


def _env(key: str, default: str | None = None) -> str:
    val = os.environ.get(key, default)
    if val is None or val == "":
        raise SystemExit(f"missing required env var: {key}")
    return val


class Api:
    def __init__(self):
        self.url = _env("API_URL").rstrip("/")
        self.token = _env("RUNNER_TOKEN")
        self.timeout = float(_env("HTTP_TIMEOUT", "15"))

    def _post(self, path: str, body: dict | None = None):
        req = urllib.request.Request(self.url + path, method="POST", data=json.dumps(body or {}).encode(),
                                     headers={"X-Runner-Token": self.token, "Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=self.timeout) as r:
            return json.load(r)

    def claim(self, limit: int) -> list[dict]:
        return self._post(f"/api/runner/claim?limit={limit}")

    def result(self, msg_id: int, error: str | None) -> str:
        return self._post(f"/api/runner/result/{msg_id}", {"error": error})["status"]


class AdbTransport:
    """adb -> SmsProvider."""

    def __init__(self):
        serial = os.environ.get("ADB_SERIAL", "")
        self.base = ["adb"] + (["-s", serial] if serial else [])
        self.serial = serial
        # > app's 30s radio wait
        self.timeout = float(_env("ADB_TIMEOUT", "45"))
        self.uri = f"content://{GATEWAY_PKG}"
        self._ready = False

    def _shell(self, cmd: str) -> tuple[bool, str]:
        try:
            p = subprocess.run(self.base + ["shell", cmd], capture_output=True, text=True, timeout=self.timeout)
        except FileNotFoundError:
            return False, "adb not found"
        except subprocess.TimeoutExpired:
            return False, "adb timed out"
        out = (p.stdout + p.stderr).strip()
        return p.returncode == 0, out

    def _call(self, method: str, arg: str | None = None) -> tuple[bool, str | None]:
        """(reached_app, error); error None = OK."""
        cmd = f"content call --uri {self.uri} --method {method}"
        if arg is not None:
            cmd += f" --arg {shlex.quote(arg)}"
        _, out = self._shell(cmd)
        m = RESULT_RE.search(out)
        if m:
            return True, None if m.group(1) == "OK" else m.group(2)
        if "Could not find provider" in out:
            return False, f"app {GATEWAY_PKG} not installed on device"
        return False, out.splitlines()[-1] if out else "no output from adb"

    def ready(self) -> bool:
        """Device up, app installed, SEND_SMS granted."""
        if self._ready:
            return True
        _, err = self._call("ping")
        if err and "permission" in err:
            self._shell(f"pm grant {GATEWAY_PKG} android.permission.SEND_SMS")
            _, err = self._call("ping")
        if err:
            log.warning("phone not ready: %s", err)
            return False
        log.info("phone ready (%s)", self.serial or "default device")
        self._ready = True
        return True

    def send(self, msg_id: int, to: str, body: str) -> str | None:
        payload = json.dumps({"id": msg_id, "to": to, "message": body})
        reached, err = self._call("send", base64.b64encode(payload.encode()).decode())
        if not reached:
            self._ready = False  # pause until phone is back
        return err


class MockTransport:
    """No phone; numbers ending in 0 fail."""

    def ready(self) -> bool:
        return True

    def send(self, msg_id: int, to: str, body: str) -> str | None:
        log.info("[mock] SMS #%d to %s: %r", msg_id, to, body)
        return "mock: radio off" if to.endswith("0") else None


def _handle_stop(*_):
    global _stop
    _stop = True


def run() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    signal.signal(signal.SIGINT, _handle_stop)
    signal.signal(signal.SIGTERM, _handle_stop)
    api = Api()
    # adb | mock
    mode = _env("SMS_TRANSPORT", "adb")
    transport = {"adb": AdbTransport, "mock": MockTransport}[mode]()
    poll = float(_env("POLL_INTERVAL", "2"))
    batch_size = int(_env("BATCH_SIZE", "10"))
    log.info("runner started; api=%s transport=%s poll=%ss batch=%d", api.url, mode, poll, batch_size)

    while not _stop:
        batch = []
        try:
            # phone down -> leave queued
            batch = api.claim(batch_size) if transport.ready() else []
            for m in batch:
                if _stop:
                    break  # unsent ones go stale and get re-queued
                error = transport.send(m["id"], m["to"], m["message"])
                status = api.result(m["id"], error)
                log.info("msg %d -> %s: %s%s", m["id"], m["to"], status, f" ({error})" if error else "")
        except (urllib.error.URLError, OSError, ValueError) as e:
            log.warning("api error: %s", e)
            batch = []
        if not batch:
            time.sleep(poll)
    log.info("runner stopped")


if __name__ == "__main__":
    run()
