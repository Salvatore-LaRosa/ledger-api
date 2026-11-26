CREATE TABLE orders (
                        id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
                        buyer_account_id    UUID        NOT NULL,
                        merchant_account_id UUID        NOT NULL,
    -- Minor units (e.g. cents), same convention as accounts.balance.
                        amount              BIGINT      NOT NULL,
                        currency            VARCHAR(3)  NOT NULL,
                        status              VARCHAR(20) NOT NULL DEFAULT 'CREATED',
    -- Optimistic locking: Hibernate bumps it on every update and fails stale writes.
                        version             BIGINT      NOT NULL DEFAULT 0,
                        created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
                        CONSTRAINT fk_orders_buyer
                            FOREIGN KEY (buyer_account_id) REFERENCES accounts (id),
                        CONSTRAINT fk_orders_merchant
                            FOREIGN KEY (merchant_account_id) REFERENCES accounts (id),
                        CONSTRAINT ck_orders_amount_positive
                            CHECK (amount > 0),
                        CONSTRAINT ck_orders_currency
                            CHECK (currency ~ '^[A-Z]{3}$'),
    -- The database only guards the set of valid values: the transition rules
    -- live in the domain (OrderStatus).
    CONSTRAINT ck_orders_status
        CHECK (status IN ('CREATED', 'PAID', 'CONFIRMED', 'CONSOLIDATED', 'CANCELLED')),
    CONSTRAINT ck_orders_distinct_accounts
        CHECK (buyer_account_id <> merchant_account_id)
);

CREATE INDEX ix_orders_buyer_account_id ON orders (buyer_account_id);
CREATE INDEX ix_orders_merchant_account_id ON orders (merchant_account_id);