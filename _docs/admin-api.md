# Admin API

Base `/api/v1`. Impl: `api/src/adminApi.js`. Worker (sender identity)
management — never customer self-service. No admin account; one shared
secret. Client: [smsGoku mobile app](./worker-mobile.md).

## Auth

`X-Admin-Token: <token>` — `ADMIN_TOKEN` in `.env/admin.env`, constant-time
compare, never in DB. Missing/wrong → `401`; unset server-side → `503`.

Generate: `node -e "console.log(require('crypto').randomBytes(32).toString('base64url'))"`
then `docker compose --env-file ./.env/db.env up -d --build`.

## `POST /admin/workers`

Body `{ name, phone, public?, subId? }`.
- `name` — required.
- `phone` — E.164, validated. Unique among active workers (`409`); revoked numbers reusable.
- `public` — bool, default `false`. `true` = visible in `GET /numbers` / selectable as `from`.
- `subId` — non-negative int or null (default). SIM subscription id the registering device sends from → `worker_tokens.sub_id`. `400` if present and not a non-negative int. `null` = device default SIM. Device-scoped; app re-adopts it each sync. API only stores/echoes.

`201 { id, name, phone, isPublic, subId }`.

## `GET /admin/workers`

`200 [{ id, name, phone, isPublic, subId, createdAt, revokedAt }, ...]` (`subId` null when unset).

## `PATCH /admin/workers/:id/revoke`

`200 { id, revoked: true }` / `404` / `409` (already revoked).

## `GET /admin/sms/pending`

Query: `workerId` (required int), `limit` (default 20, max 50).

Claims up to `limit` queued (`status 0`) rows for the worker, oldest first,
flips to `status 2` + `pulled_at` in one `SELECT ... FOR UPDATE` txn (no
double-claim). Works on revoked workers too.

`200 [{ id, to, message }, ...]` (empty = nothing waiting).

## `PATCH /admin/sms/:id/report`

Body `{ error? }` — omit/null = success, string = failure (→ `error_message`).

Flips `status 2 → 1`, sets `processed_at`, `attempts++`. Only on a pulled
row — re-report → `409`. No auto-retry.

`200 { id, processed: true, success }` / `400` / `404` / `409`.

## Worker device flow

Device acts as one worker (its `phone_number` must be that device's SIM
number). Each sync: pull pending for `workerId` → send via `SmsManager` →
report. No per-worker credential — same `X-Admin-Token`, scoped only by the
`workerId` asked for.
