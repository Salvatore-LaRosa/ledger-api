package dev.salvatore.ledger.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.salvatore.ledger.TestcontainersConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Verifies the V3 triggers with plain SQL, bypassing JPA on purpose: the point is to prove
 * that the database itself refuses to rewrite history.
 *
 * <p>Not {@code @Transactional}: PostgreSQL aborts a transaction after the first error, so
 * each statement runs in its own auto-commit transaction. Consequence: ledger rows created
 * here are never cleaned up (they cannot be deleted, that is the whole point), so every
 * test seeds its own rows with random ids and never relies on table-wide counts.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LedgerImmutabilityTest {

    private static final String IMMUTABLE_MESSAGE = "ledger rows are immutable";

    @Autowired
    private JdbcTemplate jdbc;

    private UUID accountId;
    private UUID transactionId;
    private UUID entryId;

    @BeforeEach
    void seedLedgerRows() {
        accountId = UUID.randomUUID();
        transactionId = UUID.randomUUID();
        entryId = UUID.randomUUID();

        jdbc.update("INSERT INTO accounts (id, name, type, currency) VALUES (?, ?, 'CUSTOMER', 'XTS')",
                accountId, "immutability-" + accountId);
        jdbc.update("INSERT INTO ledger_transactions (id, reference) VALUES (?, ?)",
                transactionId, "test:" + transactionId);
        jdbc.update("INSERT INTO ledger_entries (id, transaction_id, account_id, direction, amount) "
                        + "VALUES (?, ?, ?, 'CREDIT', 1000)",
                entryId, transactionId, accountId);
    }

    @Test
    void updatingAnEntryIsRejectedAndTheRowIsUnchanged() {
        assertRejected("UPDATE ledger_entries SET amount = 1 WHERE id = ?", entryId);

        assertThat(jdbc.queryForObject(
                "SELECT amount FROM ledger_entries WHERE id = ?", Long.class, entryId))
                .isEqualTo(1000L);
    }

    @Test
    void deletingAnEntryIsRejectedAndTheRowStillExists() {
        assertRejected("DELETE FROM ledger_entries WHERE id = ?", entryId);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM ledger_entries WHERE id = ?", Integer.class, entryId))
                .isEqualTo(1);
    }

    @Test
    void updatingATransactionIsRejectedAndTheRowIsUnchanged() {
        assertRejected("UPDATE ledger_transactions SET reference = 'tampered' WHERE id = ?", transactionId);

        assertThat(jdbc.queryForObject(
                "SELECT reference FROM ledger_transactions WHERE id = ?", String.class, transactionId))
                .isEqualTo("test:" + transactionId);
    }

    @Test
    void deletingATransactionIsRejectedByTheTriggerAndNotByTheForeignKey() {
        // The seeded entry also references this transaction, so a FK error would be the
        // "wrong" reason to fail: the message assertion proves the trigger fired first.
        assertRejected("DELETE FROM ledger_transactions WHERE id = ?", transactionId);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM ledger_transactions WHERE id = ?", Integer.class, transactionId))
                .isEqualTo(1);
    }

    @Test
    void truncatingEntriesIsRejected() {
        assertRejected("TRUNCATE ledger_entries");
    }

    @Test
    void truncatingTransactionsWithCascadeIsRejected() {
        assertRejected("TRUNCATE ledger_transactions CASCADE");
    }

    @Test
    void aNoOpUpdateIsRejectedToo() {
        // Documents the statement-level choice: any attempt is refused, even matching zero rows.
        assertRejected("UPDATE ledger_entries SET amount = amount WHERE false");
    }

    @Test
    void insertingNewEntriesIsStillAllowed() {
        assertThatCode(() -> jdbc.update(
                "INSERT INTO ledger_entries (id, transaction_id, account_id, direction, amount) "
                        + "VALUES (?, ?, ?, 'DEBIT', 500)",
                UUID.randomUUID(), transactionId, accountId))
                .doesNotThrowAnyException();
    }

    @Test
    void accountBalancesRemainUpdatable() {
        // Only the ledger tables are immutable: the denormalized balance must stay writable.
        assertThatCode(() -> jdbc.update(
                "UPDATE accounts SET balance = balance + 1000 WHERE id = ?", accountId))
                .doesNotThrowAnyException();
    }

    private void assertRejected(String sql, Object... args) {
        assertThatThrownBy(() -> jdbc.update(sql, args))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining(IMMUTABLE_MESSAGE);
    }
}