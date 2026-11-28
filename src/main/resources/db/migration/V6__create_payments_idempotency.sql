CREATE TABLE payments (
                          id                     UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    -- Client-supplied Idempotency-Key. The UNIQUE constraint below is the guarantee that the
    -- same payment is never executed twice, however many concurrent retries arrive.
                          idempotency_key        VARCHAR(64) NOT NULL,
    -- SHA-256 (hex) of the canonical request: the same key with another request is a client error.
                          request_hash           VARCHAR(64) NOT NULL,
                          source_account_id      UUID        NOT NULL,
                          destination_account_id UUID        NOT NULL,
    -- Minor units (e.g. cents). The currency is the one of the accounts.
                          amount                 BIGINT      NOT NULL,
                          created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
                          CONSTRAINT uq_payments_idempotency_key
                              UNIQUE (idempotency_key),
                          CONSTRAINT fk_payments_source
                              FOREIGN KEY (source_account_id) REFERENCES accounts (id),
                          CONSTRAINT fk_payments_destination
                              FOREIGN KEY (destination_account_id) REFERENCES accounts (id),
                          CONSTRAINT ck_payments_key_not_blank
                              CHECK (length(btrim(idempotency_key)) > 0),
                          CONSTRAINT ck_payments_request_hash
                              CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_payments_amount_positive
        CHECK (amount > 0),
    CONSTRAINT ck_payments_distinct_accounts
        CHECK (source_account_id <> destination_account_id)
);

CREATE INDEX ix_payments_source_account_id ON payments (source_account_id);
CREATE INDEX ix_payments_destination_account_id ON payments (destination_account_id);