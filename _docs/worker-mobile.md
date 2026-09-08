# smsJustu (mobile)

Android app at `mobileui/`. Package
`com.smsjustu.app`, app label "smsJustu". A client for the
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
  pull toggle, background-sync toggle, the configured send-as
  `workerId`. Nothing here is encrypted; the admin token is a static
  shared secret with the same blast radius as putting it in
  `.env/admin.env`, treat a device holding it accordingly.
- `SyncService.kt`, `StartServiceReceiver.kt`, `RestartScheduler.kt`,
  `SmsJustuApplication.kt` — the background-service machinery, see below.
- `SmsSender.kt` — sends one message via `SmsManager` on the device's
  default SIM, splitting into multipart if needed, and resolves once
  every part's sent-broadcast has come back (success or a specific
  `SmsManager.RESULT_ERROR_*`).
- `SendSmsReceiver.kt` — an exported, `SEND_SMS`-gated `BroadcastReceiver`
  for one-shot sends driven by a host script over adb
  (`am broadcast -n com.smsjustu.app/.SendSmsReceiver --es number … --es
  message …`, optional `--ei subId <n>`). Independent of the sync loop:
  it sends what it's handed and reports the outcome as the ordered-
  broadcast result. Used by `_script/autoscript.py`; see
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
| Create | `POST /admin/workers` | FAB → name / phone / public switch |
| Revoke | `PATCH /admin/workers/:id/revoke` | per-card "Revoke" button → confirm dialog |

Server-side validation errors (bad phone format, duplicate active
number, etc.) surface verbatim in a Snackbar — the client does no
independent phone-number validation itself.

## Sending SMS

Off by default — a device only sends once a worker is picked under
"Send as worker" in Settings, which (a) persists `Settings.workerId`
and (b) requests `SEND_SMS` at that point if not already granted.
The picker only offers active, public workers, since those are the
only ones `POST /sms` (see [`api.md`](./api.md)) will ever have queued
anything against.

**This is a manual, unverified binding** — Android has no reliable way
for the app to read back "what's this SIM's own phone number" (carrier
support for it is inconsistent and getting more locked down each
release), so there's no way to confirm the picked worker's
`phone_number` actually matches the device's SIM. Get it wrong and
messages send fine, just from a number that isn't what the recipient
expects. One worker per physical device/SIM is the only setup that
makes sense here; multi-SIM (multiple workers on one device) isn't
implemented — see the multi-SIM discussion this feature grew out of.

Every sync cycle (see below), if `workerId` is set and `SEND_SMS` is
granted: `AdminApiClient.pullPendingSms(workerId)` claims whatever's
queued, each message goes through `SmsSender.send()` on the default
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
- **`SmsJustuApplication`** — installs a
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
