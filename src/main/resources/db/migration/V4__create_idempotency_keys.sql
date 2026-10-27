CREATE TABLE idempotency_keys (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    owner_id        VARCHAR(255) NOT NULL,
    key             VARCHAR(255) NOT NULL,
    request_hash    CHAR(64)     NOT NULL,
    response_status INTEGER,
    response_body   TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT idempotency_keys_owner_key_uq UNIQUE (owner_id, key),
    CONSTRAINT idempotency_keys_response_chk CHECK ((response_status IS NULL) = (response_body IS NULL))
);
