-- System accounts are created by migration, never through the API.
-- One transit (escrow) account per supported currency. EUR is the only one for now:
-- supporting another currency means a new migration.
INSERT INTO accounts (name, type, currency)
VALUES ('Transit EUR', 'TRANSIT', 'EUR');