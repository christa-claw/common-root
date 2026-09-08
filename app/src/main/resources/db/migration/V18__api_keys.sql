-- API keys and per-key monthly usage counters (docs/api-spec.md §2, §5).
--
-- Keys are their own table, not a column on users: several live keys per
-- account is what makes rotation create-new -> move-traffic -> revoke-old
-- instead of downtime. Only a SHA-256 hash of the key is stored; key_id is
-- the 8-character display prefix shown in the account UI ("crk_a81f02xx...").
--
-- api_usage is one row per (key, calendar month UTC): two counters, never one
-- clever unit -- requests for API calls, bytes for downloads.

CREATE TABLE api_keys (
    id           VARCHAR(40) NOT NULL PRIMARY KEY,   -- typed id: key-{uuidv7}
    user_id      VARCHAR(40) NOT NULL,
    key_id       VARCHAR(8)  NOT NULL,               -- display prefix, plaintext
    key_hash     VARCHAR(64) NOT NULL,               -- SHA-256 hex of the full key
    label        VARCHAR(64) NULL,
    created_at   DATETIME    NOT NULL,
    last_used_at DATETIME    NULL,
    revoked_at   DATETIME    NULL,
    UNIQUE KEY uq_api_keys_key_id   (key_id),
    UNIQUE KEY uq_api_keys_key_hash (key_hash),
    KEY        ix_api_keys_user     (user_id),
    CONSTRAINT fk_api_keys_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE api_usage (
    api_key_id VARCHAR(40) NOT NULL,
    period     VARCHAR(6)  NOT NULL,                 -- yyyymm, UTC
    requests   BIGINT      NOT NULL DEFAULT 0,
    bytes      BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (api_key_id, period),
    CONSTRAINT fk_api_usage_key FOREIGN KEY (api_key_id) REFERENCES api_keys (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
