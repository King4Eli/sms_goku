# smsGoku (mobile)

Android app at `mobileui/`. Package
`com.smsgoku.app`, app label "smsGoku". A client for the
[Admin API](./admin-api.md): it manages worker records (sender
identities customers can pick as `from`; create/list/revoke) and,
if configured with a worker to send as, actually sends the queued
messages via the device's own default SIM — see "Sending SMS" below.

Kotlin + Jetpack Compose, minSdk 24, targetSdk 36. Networking is OkHttp
(`java.net.HttpURLConnection` on Android rejects the `PATCH` method
required by the revoke route, so plain `HttpURLConnection` isn't an
option here).

## Screens and files

- `MainActivity.kt` — single-activity Compose UI: worker list, create
  dialog, revoke confirmation, settings dialog. No navigation library;
  everything is dialogs over one screen.
- `AdminApiClient.kt` — thin wrapper over `/api/v1/admin/*`
  (`listWorkers`, `createWorker`, `revokeWorker`, `pullPendingSms`,
  `reportSmsResult`), `X-Admin-Token` on every request. Talks to the
  same admin routes documented in [`admin-api.md`](./admin-api.md).
- `Settings.kt` — `SharedPreferences` wrapper: server URL, admin token,
  pull toggle, background-sync toggle, the configured send-as `workerId`,
  and `subId` (the SIM to send from). Nothing here is encrypted; the
  admin token is a static shared secret with the same blast radius as
  putting it in `.env/admin.env`, treat a device holding it accordingly.
- `SimSupport.kt` — lists the device's active SIMs
  (`SubscriptionManager`, needs `READ_PHONE_STATE`) for the "SIM to send
  from" picker in the register-worker dialog; `simLabelFor(subId, sims)`
  for the worker-list labels; and `smsManagerFor(context, subId)` — the
  one place a `SmsManager` is bound to a subscription, shared by both
  send paths.
- `SyncService.kt`, `StartServiceReceiver.kt`, `RestartScheduler.kt`,
  `SmsGokuApplication.kt` — the background-service machinery, see below.
- `SmsSender.kt` — sends one message via `SmsManager` on the configured
  SIM (`Settings.subId`, passed in), splitting into multipart if needed,
  and resolves once every part's sent-broadcast has come back (success
  or a specific `SmsManager.RESULT_ERROR_*`).
- `SendSmsReceiver.kt` — an exported, `SEND_SMS`-gated `BroadcastReceiver`
  for one-shot sends driven by a host script over adb
  (`am broadcast -n com.smsgoku.app/.SendSmsReceiver --es number … --es
  message …`). The SIM is `Settings.subId`, not a broadcast extra.
  Independent of the sync loop: it sends what it's handed and reports the
  outcome as the ordered-broadcast result. Used by `_script/main.py`; see
  [`how-to-headless.txt`](./how-to-headless.txt).

## Setup

On first launch (or whenever the admin token is blank) a Settings
dialog opens automatically:

- **Server URL** — defaults to `https://sms-gateway.q1-site.site`;
  point it at `http://10.0.2.2:8080` from an emulator to hit a host
  machine's `docker compose` API.
- **X-Admin-Token** — the same value as `ADMIN_TOKEN` in
  `.env/admin.env`. Without it every `/admin/*` call 401s (or 503s if
  the server itself has no `ADMIN_TOKEN` set).

Saving refreshes the worker list immediately. `usesCleartextTraffic` is
on so plain `http://` server URLs work during local testing.

## Worker management

Straight CRUD-ish mapping onto the admin routes:

| Action | Route | UI |
|---|---|---|
| List | `GET /admin/workers` | main list, pull the refresh icon or reopen the app |
| Create | `POST /admin/workers` | FAB → name / phone / public switch / SIM to send from (`subId` → `worker_tokens.sub_id`; binds this device on success) |
| Revoke | `PATCH /admin/workers/:id/revoke` | per-card "Revoke" button → confirm dialog |

Server-side validation errors (bad phone format, duplicate active
number, etc.) surface verbatim in a Snackbar — the client does no
independent phone-number validation itself.

## Sending SMS

Off by default. The SIM is chosen **when a worker is registered from
this device** — the "Register worker" dialog (the `+` button) has a
**"SIM to send from"** row: "Default SIM", or one of the device's active
SIMs (listing them needs `READ_PHONE_STATE`, requested when the picker
is first opened). `POST /admin/workers` carries the choice as `subId`
and it lands on `worker_tokens.sub_id` — **that column is the source of
truth for which SIM a worker uses**. On a successful create the app also
persists `Settings.workerId` and `Settings.subId` locally and requests
`SEND_SMS` — so registering a worker here means "this device now sends
as that worker, on that SIM".

`Settings.subId` is just a local cache of the record: every sync (and
`refresh()`) re-adopts this device's worker's `sub_id` from the pulled
worker list, so a change made elsewhere propagates. Both send paths
(`SmsSender`, `SendSmsReceiver`) read the cache; nothing picks a SIM per
message. The worker list shows each worker's SIM (`simLabelFor` —
"Default SIM", the matched local SIM's label, or "SIM #n"), with "· this
device" on the one this device is configured as.

Settings still has a **"Send as worker"** dropdown to re-point an
already-configured device at a different existing worker (e.g. after a
reinstall, or a worker created elsewhere); it changes `workerId`, and
the next sync then pulls that worker's `sub_id` into the cache.

**The worker↔SIM binding is manual and unverified** — Android has no
reliable way for the app to read back "what's this SIM's own phone
number" (carrier support is inconsistent and getting more locked down
each release), so the picker can label a SIM by slot and carrier but
can't confirm its number matches the worker's `phone_number`. Get it
wrong and messages send fine, just from the wrong number. One worker =
one SIM: register each SIM's worker on the device holding that SIM. A
dual-SIM device can act as either worker by re-registering / re-picking;
running two workers off one device at once still isn't a thing.

Every sync cycle (see below), if `workerId` is set and `SEND_SMS` is
granted: `AdminApiClient.pullPendingSms(workerId)` claims whatever's
queued, each message goes through `SmsSender.send()` on the configured
SIM, and the outcome is reported back with `reportSmsResult()`
regardless of success/failure — a message that's claimed but never
reported would stay claimed server-side forever. Each attempt logs a
`SENT` or `UNDELIVERED` event (`EventLog`, tagged with `workerId` so it
also shows under that worker's own "Log"), which is what the "Sent" /
"Undelivered" counters in the Activity log actually count.

## Background sync service

Optional, off by default. The Settings dialog has two independent
switches:

- **Pull** — the master on/off for the sync service. Toggling it on
  starts `SyncService` immediately (and pulls right away); toggling it
  off stops the service. The home screen shows the current state as
  "Pull: On" / "Pull: Off".
- **Run in background** — persistence policy only: auto-starts on boot
  and restarts the service if the process is killed or crashes. It has
  no effect unless Pull is also on — both flags are checked before any
  boot/crash restart fires.

Every 60s (`SyncService.SYNC_INTERVAL_MS`) it polls `GET
/admin/workers`, then drains and sends any pending SMS for the
configured worker (see above), and keeps a low-priority,
non-dismissible notification updated with the active worker count and
last-sync time. Tapping the notification opens the app. Note the FAB /
manual refresh in `MainActivity` only re-lists workers — sending only
ever happens from this background cycle, so `Settings.pullEnabled` is
the actual on/off switch for whether messages go out at all.

Mechanics:

- **`SyncService`** — foreground service, type `specialUse` (declared
  via `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` in the manifest to avoid the
  execution-time caps Android puts on `dataSync`-type services).
  `START_STICKY` so the system re-creates it after low-memory kills.
  Acquires a partial wake lock only for the duration of each sync call,
  not continuously.
- **`StartServiceReceiver`** — `BroadcastReceiver` for
  `BOOT_COMPLETED` / `QUICKBOOT_POWERON` / `MY_PACKAGE_REPLACED` (app
  updated) and a private `RESTART_SYNC` action. Starts the service only
  if both `Settings.backgroundSyncEnabled` and `Settings.pullEnabled`
  are true; otherwise a no-op.
- **`RestartScheduler`** — schedules a `RESTART_SYNC` broadcast via
  `AlarmManager` a couple seconds out. Used by both
  `SyncService.onTaskRemoved()` (some OEMs kill services when the app
  is swiped from recents) and the crash handler below. Same
  `backgroundSyncEnabled && pullEnabled` gate as above.
- **`SmsGokuApplication`** — installs a
  `Thread.setDefaultUncaughtExceptionHandler` that calls
  `RestartScheduler` before re-throwing to the default handler, so an
  unhandled crash anywhere in the app still leaves the sync service
  restarting a couple seconds later. Also starts the service on cold
  process start if `Settings.pullEnabled` is true.

Enabling Pull also requests `POST_NOTIFICATIONS` (Android 13+,
best-effort — the service runs fine without it, the notification just
won't show) and offers a button to launch the system's "exempt from
battery optimization" dialog, since aggressive OEM battery managers can
otherwise kill it despite the foreground-service exemption.

**Known limitation**: if the user (or the OS) force-stops the app,
Android puts it into a "stopped" state that suppresses `BOOT_COMPLETED`
and other implicit broadcasts until the app is manually launched again
— there's no way around this from app code. Opening the app once after
a force-stop is enough to restore normal boot-start behavior.

## Building / installing

```bash
cd mobileui
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

No secrets are baked into the build — the admin token is entered at
runtime and stored in the app's private `SharedPreferences` only.
