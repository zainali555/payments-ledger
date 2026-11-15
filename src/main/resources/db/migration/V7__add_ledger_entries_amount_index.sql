-- History sorted by amount: GET /accounts/{id}/transactions?sort=amount,... filters on account_id and orders by amount, id.
-- Sorting by date is already served by ledger_entries_account_created_idx (V3), which Postgres can scan in either direction.
CREATE INDEX ledger_entries_account_amount_idx ON ledger_entries (account_id, amount, id);
