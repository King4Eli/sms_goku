# smsGoku (mobile)

`mobileui/`, package `com.smsgoku.app`. Kotlin + Compose, minSdk 24,
targetSdk 36, OkHttp (Android's `HttpURLConnection` rejects `PATCH`).
Client for the [Admin API](./admin-api.md): manage workers, and (if
configured) drain + send their queued SMS.

## Files

| File | Role |
|---|---|
| `MainActivity.kt` | single-activity Compose UI — worker list + dialogs |
| `AdminApiClient.kt` | wrapper over `/api/v1/admin/*` (`listWorkers`, `createWorker`, `revokeWorker`, `pullPendingSms`, `reportSmsResult`), `X-Admin-Token` on all |
| `Settings.kt` | `SharedPreferences`: `baseUrl`, `adminToken`, `pullEnabled`, `backgroundSyncEnabled`, `workerId`, `subId`. Not encrypted |
| `SimSupport.kt` | `listSims` (`SubscriptionManager`, needs `READ_PHONE_STATE`) for the register dialog; `simLabelFor` for list labels; `smsManagerFor(ctx, subId)` — the one `SmsManager`↔subscription bind |
| `SmsSender.kt` | send one msg on `Settings.subId`, multipart-aware, resolves on sent-broadcasts |
| `SendSmsReceiver.kt` | exported, `SEND_SMS`-gated receiver for adb-driven one-shot sends (`am broadcast -n com.smsgoku.app/.SendSmsReceiver --es number … --es message …`); SIM = `Settings.subId`; used by `_script/main.py` |
| `SyncService.kt` + `StartServiceReceiver.kt` + `RestartScheduler.kt` + `SmsGokuApplication.kt` | background-sync machinery |

## Setup

First launch (or blank token) opens Settings:
- **Server URL** — default `https://sms-gateway.q1-site.site`; emulator → `http://10.0.2.2:8080`.
- **X-Admin-Token** — `ADMIN_TOKEN` from `.env/admin.env`. Missing → `401`/`503`.

`usesCleartextTraffic` on for local `http://`.

## Worker management

| Action | Route | UI |
|---|---|---|
| List | `GET /admin/workers` | main list / refresh icon |
| Create | `POST /admin/workers` | FAB → name / phone / public / **SIM to send from** (`subId` → `worker_tokens.sub_id`; binds this device) |
| Revoke | `PATCH /admin/workers/:id/revoke` | per-card button + confirm |

Server validation errors surface verbatim in a Snackbar.

## Sending SMS

- **SIM is chosen at registration.** The "Register worker" dialog's "SIM to
  send from" row (Default SIM, or an active SIM — needs `READ_PHONE_STATE`)
  → `POST /admin/workers { subId }` → `worker_tokens.sub_id`, **the source
  of truth**. On success the app also sets `Settings.workerId` + `subId`
  and requests `SEND_SMS`.
- `Settings.subId` is a cache: every sync / `refresh()` re-adopts this
  device's worker's `sub_id` from the pulled list. Both send paths read the
  cache; no per-message SIM choice.
- Worker list shows each worker's SIM (`simLabelFor`), "· this device" on
  the configured one.
- Settings "**Send as worker**" dropdown re-points `workerId` only; next
  sync pulls that worker's `sub_id`.
- Binding is **manual/unverified** — Android won't reliably report a SIM's
  own number, so a wrong pick sends fine from the wrong number. One worker
  = one SIM = one device.

Each sync, if `workerId` set + `SEND_SMS` granted: `pullPendingSms` →
`SmsSender.send` → `reportSmsResult` (always). Logs `SENT`/`UNDELIVERED`
per attempt (`EventLog`, tagged `workerId`).

## Background sync

Off by default. Two switches:
- **Pull** — master on/off; starts/stops `SyncService`. Sending only ever
  happens from this cycle.
- **Run in background** — persistence only (boot-start + crash-restart);
  no effect unless Pull is also on.

`SyncService`: foreground service, `specialUse` type, `START_STICKY`, wake
lock per cycle, polls every 60s (`SYNC_INTERVAL_MS`), keeps a min-priority
notification. `StartServiceReceiver` handles `BOOT_COMPLETED` /
`MY_PACKAGE_REPLACED` / `RESTART_SYNC` (gated on both flags).
`RestartScheduler` re-arms via `AlarmManager` after task-removal / crash.
`SmsGokuApplication` installs a crash handler + cold-start.

Pull also requests `POST_NOTIFICATIONS` and offers the battery-optimization
exemption dialog.

**Limitation:** a force-stop suppresses `BOOT_COMPLETED` etc. until the app
is opened once manually.

## Build

```bash
cd mobileui
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

No secrets baked in — token is entered at runtime.
