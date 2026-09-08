# API

Base `/api/v1`. Impl: `api/src/userApi.js`, `api/src/adminApi.js`.
Admin routes: [`admin-api.md`](./admin-api.md).

## Auth

| Audience | Header | Store |
|---|---|---|
| Customer | `X-Api-Key` | `api_keys` |
| Admin | `X-Admin-Token` | static secret, `.env/admin.env` |

Separate credential spaces; neither authenticates the other's routes.

## `POST /users/token`

Open, no rate limit. Body `{ email, phone, label? }`.
- `email` required, HTML5 pattern.
- `phone` required, E.164 via `libphonenumber-js`; `country` derived.
- Existing email → updates that user, no duplicate.

`201 { id, userId, email, phone, country, label, apiKey }` — `apiKey` shown once (only SHA-256 stored).

## `GET /numbers`

Auth: customer. `worker_tokens` where `revoked_at IS NULL AND is_public = 1`.

`200 [{ id, phone }, ...]` — `id` is what `POST /sms` `from` wants; `phone` display-only.

## `POST /sms`

Auth: customer. Body `{ to, from, message }`.
- `to` — validated/normalized like `phone` above.
- `from` — worker `id` (int) from `GET /numbers`, not a phone number. Must be active + public, else `400`.

Rate limit: `api_keys.daily_sms_limit` (default 10) per rolling 24h, counted from `sms_queue`. `429` over.

`201 { id, to, from, message, status: 0 }`. Worker device pulls from `status 0` — see [`admin-api.md`](./admin-api.md).

## Rate limiting

Only `/sms`, by `api_keys.daily_sms_limit` (read live): `UPDATE api_keys SET daily_sms_limit = ? WHERE id = ?`.
