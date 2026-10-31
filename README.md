# SMS Goku

```
VPS     web :9161 ──X-API-Key──▶ api :9160 ──▶ Postgres
                                   ▲
                                   │ HTTP, X-Runner-Token (claim / result)
LOCAL   pythonrunner ──adb, USB──▶ phone: SmsProvider ─▶ SmsManager ─▶ SMS
```

| Folder          | Where | What                                         |
|-----------------|-------|----------------------------------------------|
| `api/`          | VPS   | FastAPI, models, message queue               |
| `web/`          | VPS   | nginx + simulator (proxies `/api`)           |
| `pythonrunner/` | local | claims messages, sends via adb (stdlib only) |
| `mobile/`       | phone | Android app: one adb-driven ContentProvider  |
| `.app.env/`     |       | `*.env` (secrets, ignored) + `*.env.example` |

## Run

VPS:

```bash
for f in db api; do cp .app.env/$f.env.example .app.env/$f.env; done   # fill in
docker compose -f postgres.yml up -d    # db, once
docker compose up -d --build
```

Local (phone on USB):

```bash
cp .app.env/runner.env.example .app.env/runner.env   # API_URL, RUNNER_TOKEN (= api.env)
adb devices                                          # phone = "device"
set -a; . runner.env; set +a                # load into env
python3 pythonrunner/main.py
```

- Simulator: http://<vps>:9161 (key = `DEMO_API_KEY`)
- Docs: http://<vps>:9160/docs
- No phone: `SMS_TRANSPORT=mock python3 pythonrunner/main.py` (numbers ending in 0 fail)

## Phone setup

1. Enable USB debugging, plug in, accept RSA.
2. Install:
   ```bash
   cd mobile && ./gradlew assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

No UI, no background service; each `content call` starts it on demand. Runner auto-grants `SEND_SMS`.
Multiple devices: set `ADB_SERIAL`.

## Phone protocol

```bash
adb shell content call --uri content://com.smsgoku.gateway --method ping
# r=OK ready | r=ERR SEND_SMS permission not granted

adb shell content call --uri content://com.smsgoku.gateway --method send \
    --arg "$(printf '{"id":1,"to":"+15551234567","message":"hi"}' | base64 -w0)"
# r=OK parts=1 | r=ERR radio off
```

- base64 JSON: survives the shell.
- Blocks until every part is confirmed (≤30s).
- Shell (uid 2000) / root only.
- Provider, not broadcast: a receiver awaiting "sent" would deadlock.

## Runner

Loop: phone ready? → `POST /api/runner/claim` (`queued → sending`, `FOR UPDATE SKIP LOCKED`) → send →
`POST /api/runner/result/{id}` → `sent`, or retry after `RETRY_BACKOFF_SECONDS × attempt` up to
`MAX_ATTEMPTS`, then `failed`. Phone down → messages stay `queued`. Stuck `sending` >
`STALE_SENDING_SECONDS` (runner died) → re-queued.

## Database

[api/app/models.py](api/app/models.py), created via `create_all()` on API start (no alters:
`docker compose down -v` or add Alembic).

- `api_keys`: name, prefix, sha256 hash, active
- `messages`: to, body, status (`queued → sending → sent | failed`), attempts, last_error,
  next_attempt_at, timestamps

## API

```js
const BASE = "http://localhost:9160";
const KEY = "...";          // client API key
const ADMIN_TOKEN = "...";  // from api.env
const RUNNER_TOKEN = "..."; // from api.env

const client = { "X-API-Key": KEY, "Content-Type": "application/json" };
const admin = { "X-Admin-Token": ADMIN_TOKEN, "Content-Type": "application/json" };
const runner = { "X-Runner-Token": RUNNER_TOKEN, "Content-Type": "application/json" };
const call = async (path, opts) => (await fetch(BASE + path, opts)).json();

// send (client)
await call("/api/sms", {
  method: "POST",
  headers: client,
  body: JSON.stringify({ to: "+15551234567", message: "hello" }),
});
await call("/api/sms", { headers: client });    // list own messages
await call("/api/sms/1", { headers: client });  // one message

// admin
await call("/api/admin/keys", {
  method: "POST",
  headers: admin,
  body: JSON.stringify({ name: "customer-a" }),
});                                                        // returns the raw key once
await call("/api/admin/keys", { headers: admin });
await call("/api/admin/keys/2", { method: "DELETE", headers: admin });
await call("/api/admin/stats", { headers: admin });

// runner (pythonrunner does this)
await call("/api/runner/claim?limit=10", { method: "POST", headers: runner });  // [{id, to, message}]
await call("/api/runner/result/1", {
  method: "POST",
  headers: runner,
  body: JSON.stringify({ error: null }),                   // or "radio off"
});
```
