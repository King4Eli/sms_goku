-- SMS Processing schema. This is the ONLY copy - the API reads and
-- executes this exact file on startup (api/src/db.js). Nothing in /api
-- duplicates or hardcodes any SQL. Statements are CREATE TABLE IF NOT
-- EXISTS plus a few additive ALTER TABLE ... ADD COLUMN migrations for
-- databases created before a column existed; re-running this file
-- against an already-migrated database (which is what happens on every
-- API startup) is a no-op - db.js swallows "already exists" / "duplicate
-- column" errors - and a plain SQL import against a fresh database works
-- too.
--
-- Requires MySQL 8.0.16+ (enforced CHECK constraints) and 8.0.1+ (SKIP LOCKED).

CREATE TABLE IF NOT EXISTS users (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  email VARCHAR(255) NOT NULL UNIQUE,
  phone_number VARCHAR(32) NOT NULL, -- always stored E.164-normalized, e.g. +15551234567
  country CHAR(2) NULL,              -- ISO 3166-1 alpha-2, derived from phone_number
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

-- Customer-facing API keys. Only these may submit SMS; they may never
-- authenticate against the worker endpoints.
CREATE TABLE IF NOT EXISTS api_keys (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT UNSIGNED NOT NULL,
  key_hash CHAR(64) NOT NULL UNIQUE, -- sha256 hex digest of the plaintext key
  label VARCHAR(255) NULL,
  daily_sms_limit INT UNSIGNED NOT NULL DEFAULT 10, -- max /sms submissions per rolling 24h for this key
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  revoked_at TIMESTAMP NULL,
  CONSTRAINT fk_api_keys_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB;

-- Sender identities ("workers") a customer can pick as 'from' in POST
-- /sms - admin-managed only, see admin-api.md. Never assigned to a
-- specific customer ('is_public' is the only visibility control, see
-- userApi.js) and never issued a credential of their own - there's no
-- worker-facing auth, see the pull/report flow in admin-api.md, which
-- authenticates with the same shared admin token as everything else.
CREATE TABLE IF NOT EXISTS worker_tokens (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(255) NOT NULL,
  phone_number VARCHAR(32) NOT NULL, -- E.164-normalized "from" number this worker sends as
  is_public TINYINT(1) NOT NULL DEFAULT 0, -- 1 = customers can see/select this number (GET /numbers, POST /sms 'from')
  -- SIM subscription id the registering device sends this worker's SMS
  -- from, chosen in the mobileui "Register worker" dialog. NULL = the
  -- device's default SMS SIM. Device-scoped by nature (a SIM enumerates
  -- differently per device) - it's the binding for the one device that
  -- holds this worker's SIM, and the app re-adopts it from here on sync.
  sub_id INT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  revoked_at TIMESTAMP NULL,
  -- A revoked worker's number frees up for reuse - uniqueness should only
  -- apply among active workers, not the full history. MySQL has no
  -- native partial/filtered unique index, so this generated column
  -- collapses to NULL once revoked; UNIQUE indexes treat every NULL as
  -- distinct, so any number of revoked rows (or one revoked + one fresh
  -- active row) can share a phone_number, while two simultaneously
  -- active rows still can't. All lookups that key off phone_number
  -- already filter revoked_at IS NULL (see userApi.js), so they keep
  -- resolving to at most one row.
  active_phone_number VARCHAR(32)
    GENERATED ALWAYS AS (CASE WHEN revoked_at IS NULL THEN phone_number END) VIRTUAL,
  UNIQUE KEY uq_worker_tokens_active_phone_number (active_phone_number)
) ENGINE=InnoDB;

-- Additive migration for databases created before sub_id existed. Errors
-- 1060 (duplicate column) / 1091 are swallowed by db.js once applied.
ALTER TABLE worker_tokens ADD COLUMN sub_id INT NULL AFTER is_public;

CREATE TABLE IF NOT EXISTS sms_queue (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT UNSIGNED NOT NULL,
  api_key_id BIGINT UNSIGNED NOT NULL,
  worker_token_id BIGINT UNSIGNED NOT NULL, -- the "from" worker picked at submission (see POST /sms in userApi.js)
  to_number VARCHAR(32) NOT NULL,
  message TEXT NOT NULL,
  status TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0=queued, 1=processed, 2=pulled',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  pulled_at TIMESTAMP NULL,
  processed_at TIMESTAMP NULL,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  attempts INT UNSIGNED NOT NULL DEFAULT 0,
  error_message TEXT NULL,
  CONSTRAINT chk_sms_queue_status CHECK (status IN (0, 1, 2)),
  CONSTRAINT fk_sms_queue_user FOREIGN KEY (user_id) REFERENCES users(id),
  CONSTRAINT fk_sms_queue_api_key FOREIGN KEY (api_key_id) REFERENCES api_keys(id),
  CONSTRAINT fk_sms_queue_worker_token FOREIGN KEY (worker_token_id) REFERENCES worker_tokens(id),
  INDEX idx_sms_queue_status_created (status, created_at),
  -- Supports the per-API-key daily submission limit (COUNT of sms_queue
  -- rows for a given api_key_id in the trailing 24h) without a separate
  -- rate limit table.
  INDEX idx_sms_queue_api_key_created (api_key_id, created_at),
  -- Backs fk_sms_queue_worker_token and the admin pull/report routes'
  -- claim query (WHERE worker_token_id = ? AND status = 0 ORDER BY
  -- created_at - see "GET /admin/sms/pending" in admin-api.md).
  INDEX idx_sms_queue_worker_status_created (worker_token_id, status, created_at)
) ENGINE=InnoDB;
