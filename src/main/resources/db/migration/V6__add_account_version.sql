-- Optimistic-locking version for JPA (@Version). Backstop behind the FOR UPDATE row lock: a stale write fails.
ALTER TABLE accounts ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
