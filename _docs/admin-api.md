# Admin API

Base URL: `/api/v1`. Implementation: `api/src/adminApi.js`. Worker
(sender identity) management — the one thing customers can never do for
themselves. There is no admin *account*: a single shared secret gates
every route here. The only client is the [smsGoku mobile
app](./worker-mobile.md) — there's no CLI or other path to register a
worker.

## Auth

```
X-Admin-Token: <token>
```

The token lives in `.env/admin.env` (`ADMIN_TOKEN=...`), loaded the same
way `.env/db.env` is (see `docker-compose.yml`) — never committed
(`.env/` is gitignored), never stored in the database, compared with a
constant-time check. If `ADMIN_TOKEN` isn't set, every route here
responds `503`. `401` on a missing or wrong token.

Generate one:

```bash
node -e "console.log(require('crypto').randomBytes(32).toString('base64url'))"
```

Put it in `.env/admin.env` as `ADMIN_TOKEN=<value>`, then
`docker compose --env-file ./.env/db.env up -d --build` to pick it up
(compose needs a restart, not just a file edit, to re-read `env_file`).

## `POST /admin/workers`

Body: `{ name, phone, public?, subId? }`.

- `name` — required, any string.
- `phone` — required, validated/normalized like `phone` in
  `POST /users/token`. Unique among active (non-revoked) workers —
  `409` if another *active* worker already has it. A revoked worker's
  number is free to reuse.
- `public` — optional boolean, default `false`. `true` = visible to every
  customer via `GET /numbers`, selectable as `from` in `POST /sms`.
  Workers are never assigned to a specific customer — `public` is the
  only visibility control; a private worker isn't selectable by anyone
  through the customer-facing API. There's currently no other way to
  reach a worker at all — see the note on `sms_queue` below.
- `subId` — optional non-negative integer, default `null`. The SIM
  subscription id the registering device sends this worker's SMS from,
  chosen in the mobileui "Register worker" dialog (`400` if present and
  not a non-negative integer). Stored on `worker_tokens.sub_id`;
  `null` = the device's default SMS SIM. It's device-scoped by nature
  (a SIM enumerates differently per device) — the binding for the one
  device holding this worker's SIM, which the app re-adopts from here
  on each sync. The API never sends SMS itself, so it only stores and
  echoes this.

`201`: `{ id, name, phone, isPublic, subId }`.

## `GET /admin/workers`

Every worker.

`200`: `[{ id, name, phone, isPublic, subId, createdAt, revokedAt }, ...]`
(`subId` is `null` when unset).

## `PATCH /admin/workers/:id/revoke`

Revokes any worker — stops it being selectable as `from` in `POST /sms`.

`200` `{ id, revoked: true }` / `404` (no such id) / `409` (already
revoked).

## `GET /admin/sms/pending`

Query: `workerId` (required, integer — a `worker_tokens.id`), `limit`
(optional, default `20`, capped at `50`).

Claims up to `limit` queued (`status = 0`) `sms_queue` rows for that
worker — oldest first — and flips them to `status = 2` (pulled,
`pulled_at` set) as part of the same transaction (`SELECT ... FOR
UPDATE`), so two devices polling at once can't both claim, and thus
both send, the same message. Returns whatever it claimed; an empty
array means nothing was waiting.

`200`: `[{ id, to, message }, ...]`. Doesn't require the worker to still
be active/public — a message queued before a revoke is still delivered.

## `PATCH /admin/sms/:id/report`

Body: `{ error? }` — omit (or send `null`) to report success, or a
string to report failure (stored verbatim in `error_message`).

Closes the loop on one message this device previously pulled: flips
`status` from `2` (pulled) to `1` (processed), sets `processed_at`,
increments `attempts`. Only works on a row currently in the pulled
state — `409` on a re-report (e.g. a retried request after a response
that got lost in transit) rather than silently double-counting.
There's no automatic retry of failed sends; `attempts`/`error_message`
are there for a future retry policy, not read by anything yet.

`200`: `{ id, processed: true, success }` / `400` (bad id/body) / `404`
(no such id) / `409` (wasn't in the pulled state).

## Worker device flow

This pull/report pair is what the [smsGoku mobile
app](./worker-mobile.md) uses to actually send: a device is configured
to act as one specific worker (its `phone_number` has to genuinely be
that device's own SIM number, or recipients would see the wrong
sender), and on each sync cycle pulls pending messages for that
`workerId`, sends each via the device's default SIM
(`SmsManager`), and reports the outcome. There's still no per-worker
credential — the device authenticates with the same shared
`X-Admin-Token` as the rest of this API, scoped only by which
`workerId` it asks for.
