CREATE TABLE accounts (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    owner_id   VARCHAR(255),
    type       VARCHAR(16) NOT NULL,
    currency   CHAR(3)     NOT NULL,
    balance    BIGINT      NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT accounts_type_chk CHECK (type IN ('CUSTOMER', 'EXTERNAL')),
    CONSTRAINT accounts_currency_chk CHECK (currency IN ('EUR', 'USD')),
    CONSTRAINT accounts_balance_chk CHECK (balance >= 0 OR type = 'EXTERNAL'),
    CONSTRAINT accounts_owner_chk CHECK ((type = 'CUSTOMER') = (owner_id IS NOT NULL)),
    -- lets ledger_entries and transfers reference (id, currency) so currencies cannot drift
    CONSTRAINT accounts_id_currency_uq UNIQUE (id, currency)
);

-- exactly one EXTERNAL account per currency
CREATE UNIQUE INDEX accounts_external_currency_uq ON accounts (currency) WHERE type = 'EXTERNAL';

CREATE INDEX accounts_owner_idx ON accounts (owner_id) WHERE owner_id IS NOT NULL;
