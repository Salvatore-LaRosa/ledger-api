CREATE TABLE accounts (
                          id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
                          name       VARCHAR(100) NOT NULL,
                          type       VARCHAR(20)  NOT NULL,
                          currency   VARCHAR(3)   NOT NULL,
                          balance    BIGINT       NOT NULL DEFAULT 0,
                          created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
                          CONSTRAINT ck_accounts_type
                              CHECK (type IN ('CUSTOMER', 'MERCHANT', 'TRANSIT', 'EXTERNAL')),
                          CONSTRAINT ck_accounts_currency
                              CHECK (currency ~ '^[A-Z]{3}$'),
    -- EXTERNAL accounts model the outside world (funding source) and may go negative.
    CONSTRAINT ck_accounts_balance_non_negative
        CHECK (type = 'EXTERNAL' OR balance >= 0)
);

-- At most one transit (escrow) account per currency.
CREATE UNIQUE INDEX uq_accounts_transit_per_currency
    ON accounts (currency) WHERE type = 'TRANSIT';

COMMENT ON COLUMN accounts.balance IS
    'Minor units (e.g. cents). Denormalized: ledger entries are the source of truth.';