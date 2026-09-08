-- SMSgoku schema - the ONLY copy. api/src/db.js runs it on every startup;
-- all CREATE TABLE IF NOT EXISTS, so re-running is a no-op. Needs MySQL
-- 8.0.16+ (CHECK) / 8.0.1+ (SKIP LOCKED).

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

-- Sender identities ("workers"), admin-managed only (admin-api.md). Not
-- customer-scoped; is_public is the only visibility control. No worker
-- credential - the shared admin token gates the pull/report flow.
CREATE TABLE IF NOT EXISTS worker_tokens (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(255) NOT NULL,
  phone_number VARCHAR(32) NOT NULL, -- E.164 "from" number this worker sends as
  is_public TINYINT(1) NOT NULL DEFAULT 0, -- 1 = shown in GET /numbers, usable as POST /sms 'from'
  sub_id INT NULL, -- SIM subscription id the registering device sends from; NULL = its default SIM
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  revoked_at TIMESTAMP NULL,
  -- NULL once revoked so a freed number can be reused; UNIQUE treats NULLs
  -- as distinct, so at most one *active* row per phone_number.
  active_phone_number VARCHAR(32)
    GENERATED ALWAYS AS (CASE WHEN revoked_at IS NULL THEN phone_number END) VIRTUAL,
  UNIQUE KEY uq_worker_tokens_active_phone_number (active_phone_number)
) ENGINE=InnoDB;

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
