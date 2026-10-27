CREATE TABLE ledger_entries (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transfer_id BIGINT      NOT NULL REFERENCES transfers (id),
    account_id  BIGINT      NOT NULL,
    direction   VARCHAR(6)  NOT NULL,
    amount      BIGINT      NOT NULL,
    currency    CHAR(3)     NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ledger_entries_direction_chk CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ledger_entries_amount_chk CHECK (amount > 0),
    CONSTRAINT ledger_entries_account_fk FOREIGN KEY (account_id, currency) REFERENCES accounts (id, currency)
);

-- one debit and one credit per transfer at most
CREATE UNIQUE INDEX ledger_entries_transfer_direction_uq ON ledger_entries (transfer_id, direction);

-- history queries: an account's entries, newest first
CREATE INDEX ledger_entries_account_created_idx ON ledger_entries (account_id, created_at DESC, id DESC);

-- entries are append-only: corrections are new entries
CREATE FUNCTION ledger_entries_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'ledger_entries is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER ledger_entries_no_update_delete
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION ledger_entries_append_only();

CREATE TRIGGER ledger_entries_no_truncate
    BEFORE TRUNCATE ON ledger_entries
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_entries_append_only();
