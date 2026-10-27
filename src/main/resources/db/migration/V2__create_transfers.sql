CREATE TABLE transfers (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    type                 VARCHAR(16) NOT NULL,
    status               VARCHAR(16) NOT NULL,
    source_account_id    BIGINT      NOT NULL,
    target_account_id    BIGINT      NOT NULL,
    amount               BIGINT      NOT NULL,
    currency             CHAR(3)     NOT NULL,
    reverses_transfer_id BIGINT,
    created_by           VARCHAR(255) NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT transfers_type_chk CHECK (type IN ('TRANSFER', 'DEPOSIT', 'REVERSAL')),
    CONSTRAINT transfers_status_chk CHECK (status IN ('COMPLETED', 'REVERSED')),
    CONSTRAINT transfers_amount_chk CHECK (amount > 0),
    CONSTRAINT transfers_accounts_chk CHECK (source_account_id <> target_account_id),
    -- only plain transfers can end up REVERSED
    CONSTRAINT transfers_reversed_chk CHECK (status = 'COMPLETED' OR type = 'TRANSFER'),
    CONSTRAINT transfers_reversal_link_chk CHECK ((type = 'REVERSAL') = (reverses_transfer_id IS NOT NULL)),
    CONSTRAINT transfers_source_fk FOREIGN KEY (source_account_id, currency) REFERENCES accounts (id, currency),
    CONSTRAINT transfers_target_fk FOREIGN KEY (target_account_id, currency) REFERENCES accounts (id, currency),
    CONSTRAINT transfers_reverses_fk FOREIGN KEY (reverses_transfer_id) REFERENCES transfers (id),
    -- one reversal per transfer, enforced even in a race
    CONSTRAINT transfers_reverses_uq UNIQUE (reverses_transfer_id)
);

CREATE INDEX transfers_source_idx ON transfers (source_account_id);
CREATE INDEX transfers_target_idx ON transfers (target_account_id);
