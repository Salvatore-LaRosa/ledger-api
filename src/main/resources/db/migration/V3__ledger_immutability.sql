-- Ledger rows are append-only: mistakes are fixed with a reversal (a new transaction),
-- never by rewriting history. @Immutable on the JPA entities only stops Hibernate;
-- native SQL or a manual psql session would still go through. These triggers enforce
-- the rule inside the database itself.

CREATE FUNCTION forbid_ledger_modification() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'ledger rows are immutable: % on % is not allowed, create a reversal instead',
        TG_OP, TG_TABLE_NAME
        USING ERRCODE = 'restrict_violation';
END;
$$;

-- Statement-level on purpose: a single trigger per table also covers TRUNCATE
-- (which never fires row-level triggers) and rejects no-op statements too.
CREATE TRIGGER trg_ledger_transactions_immutable
    BEFORE UPDATE OR DELETE OR TRUNCATE ON ledger_transactions
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_ledger_modification();

CREATE TRIGGER trg_ledger_entries_immutable
    BEFORE UPDATE OR DELETE OR TRUNCATE ON ledger_entries
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_ledger_modification();