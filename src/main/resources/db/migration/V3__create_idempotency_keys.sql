CREATE TABLE idempotency_keys (
    idempotency_key VARCHAR(150) PRIMARY KEY,
    request_hash    VARCHAR(64)  NOT NULL,
    http_status     INTEGER      NULL,
    response_json   JSONB        NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
