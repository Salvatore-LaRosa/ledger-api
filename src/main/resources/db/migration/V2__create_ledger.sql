CREATE TABLE ledger_transactions (
                                     id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    -- Business idempotency key (e.g. 'order:<id>:paid'). The UNIQUE constraint is
    -- the guarantee: a retry can never post the same movement twice.
                                     reference      VARCHAR(100) NOT NULL,
    -- Set only when this transaction reverses another one (see reversal handling later).
                                     reversal_of_id UUID,
                                     created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
                                     CONSTRAINT uq_ledger_transactions_reference UNIQUE (reference),
    -- A transaction can be reversed at most once (NULLs are not considered equal).
                                     CONSTRAINT uq_ledger_transactions_reversal_of UNIQUE (reversal_of_id),
                                     CONSTRAINT fk_ledger_transactions_reversal_of
                                         FOREIGN KEY (reversal_of_id) REFERENCES ledger_transactions (id),
                                     CONSTRAINT ck_ledger_transactions_reference_not_blank
                                         CHECK (length(btrim(reference)) > 0)
);

CREATE TABLE ledger_entries (
                                id             UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
                                transaction_id UUID        NOT NULL,
                                account_id     UUID        NOT NULL,
    -- DEBIT: money leaves the account. CREDIT: money enters the account.
                                direction      VARCHAR(6)  NOT NULL,
    -- Always positive, in minor units (e.g. cents). The sign comes from direction.
                                amount         BIGINT      NOT NULL,
    -- No ON DELETE CASCADE on purpose: ledger history must never disappear.
                                CONSTRAINT fk_ledger_entries_transaction
                                    FOREIGN KEY (transaction_id) REFERENCES ledger_transactions (id),
                                CONSTRAINT fk_ledger_entries_account
                                    FOREIGN KEY (account_id) REFERENCES accounts (id),
                                CONSTRAINT ck_ledger_entries_direction
                                    CHECK (direction IN ('DEBIT', 'CREDIT')),
                                CONSTRAINT ck_ledger_entries_amount_positive
                                    CHECK (amount > 0)
);

CREATE INDEX ix_ledger_entries_transaction ON ledger_entries (transaction_id);
CREATE INDEX ix_ledger_entries_account ON ledger_entries (account_id);