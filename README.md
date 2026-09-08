# smsgoku

SMS gateway: customers queue messages via an API; real Android phones
("workers") send them from their own SIM and report back.

## Flow

```
customer → POST /sms ─┐
                      ▼
              sms_queue (status 0)
                      ▲  pull (claims → status 2)
   worker device ─────┤
   (mobileui app or   ▼  send via SIM, then report (→ status 1)
    _script/main.py)
```

- `from` on `POST /sms` is a **worker id** (a public sender identity).
- A worker's `phone_number` must be the sending device's real SIM number
  (unverifiable — manual binding).
- One worker = one SIM = one device.

## Components

| Dir | What | Port |
|---|---|---|
| `api/` | Node/Express — customer API + admin API, MySQL | container `:8080` → `10.50.0.2:9002` |
| `webui/` | nginx + static pages — sign up, get API key, send SMS; proxies `/api/` → `smsgoku_api:8080` | `10.50.0.2:9001` |
| `mobileui/` | Android worker app (`com.smsgoku.app`) — manage workers, drain + send queued SMS | — |
| `_script/main.py` | host-side worker — polls admin API, sends by `adb` broadcast to the mobileui app | — |
| `_docs/` | `schema.sql`, [`api.md`](_docs/api.md), [`admin-api.md`](_docs/admin-api.md), [`worker-mobile.md`](_docs/worker-mobile.md) | — |

## Config

`.env/` (gitignored, not committed):
- `.env/admin.env` — `ADMIN_TOKEN=<secret>` (gates every `/admin/*` route)
- `.env/db.env` — `DB_HOST` / `DB_PORT` / `DB_USERNAME` / `DB_PASSWORD` / `DB_DATABASE` (external MySQL, 8.0.16+)

Generate a token:
`node -e "console.log(require('crypto').randomBytes(32).toString('base64url'))"`

## Run

```bash
# schema: apply _docs/schema.sql to the DB once (the container does not)
docker compose --env-file ./.env/db.env up -d --build
```

- webui: `http://10.50.0.2:9001`
- API: `http://10.50.0.2:9002/api/v1`

## Auth

| Audience | Header |
|---|---|
| Customer | `X-Api-Key` (from `POST /users/token`, shown once) |
| Admin / worker | `X-Admin-Token` (= `ADMIN_TOKEN`) |

## Worker: mobileui app

```bash
cd mobileui && ./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Settings → server URL + admin token. Register a worker (`+` button) and
pick its SIM; that binds the device. Enable **Pull** to send. See
[`worker-mobile.md`](_docs/worker-mobile.md).

## Worker: `_script/main.py` (host + adb)

Drives the mobileui app's `SendSmsReceiver` over adb from a host machine.
Config in `_script/.env` (`PULLING_SERVER`, `ADMIN_TOKEN`, …); the worker
id is read off the connected device each poll — nothing to set.

```bash
python _script/main.py --check   # validate config + API + adb
python _script/main.py --start   # poll loop
```
