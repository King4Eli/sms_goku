#!/usr/bin/env python3
"""Queue an SMS via the API and wait for the runner to send it. Stdlib only.

    API_KEY=sk_... python3 pythonrunner/send.py "hello"
    API_KEY=sk_... python3 pythonrunner/send.py "hello" --to +15551234567
"""

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request

DEFAULT_URL = "https://sms-v2-api.vintolab.com"
DEFAULT_TO = "+18506317322"


def call(url: str, key: str, method: str, path: str, body: dict | None = None) -> dict:
    req = urllib.request.Request(url + path, method=method,
                                 data=json.dumps(body).encode() if body is not None else None,
                                 headers={"X-API-Key": key, "Content-Type": "application/json",
                                          # default Python-urllib UA gets 403 from WAFs/Cloudflare
                                          "User-Agent": "sms-goku-send"})
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            return json.load(r)
    except urllib.error.HTTPError as e:
        sys.exit(f"HTTP {e.code}: {e.read().decode(errors='replace')}")


def main():
    p = argparse.ArgumentParser(description="send an SMS")
    p.add_argument("message")
    p.add_argument("--to", default=DEFAULT_TO)
    p.add_argument("--url", default=os.environ.get("SMS_API_URL", DEFAULT_URL))
    p.add_argument("--wait", type=float, default=90, help="seconds to wait for sent/failed; 0 = don't wait")
    a = p.parse_args()

    key = os.environ.get("API_KEY")
    if not key:
        sys.exit("missing required env var: API_KEY")
    url = a.url.rstrip("/")

    m = call(url, key, "POST", "/api/sms", {"to": a.to, "message": a.message})
    print(f"queued #{m['id']} -> {m['to']}")

    deadline = time.monotonic() + a.wait
    while m["status"] in ("queued", "sending") and time.monotonic() < deadline:
        time.sleep(2)
        m = call(url, key, "GET", f"/api/sms/{m['id']}")
    print(f"#{m['id']} {m['status']}" + (f": {m['last_error']}" if m["last_error"] else ""))
    sys.exit(0 if m["status"] == "sent" or a.wait == 0 else 1)


if __name__ == "__main__":
    main()
