package dev.salvatore.ledger.repository;

import dev.salvatore.ledger.TestcontainersConfiguration;
import dev.salvatore.ledger.domain.account.Account;
import dev.salvatore.ledger.domain.account.AccountType;
import dev.salvatore.ledger.domain.ledger.EntryDirection;
import dev.salvatore.ledger.domain.ledger.LedgerEntry;
import dev.salvatore.ledger.domain.ledger.LedgerTransaction;
import dev.salvatore.ledger.domain.ledger.LedgerTransaction.EntryDraft;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.Currency;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Runs against a real PostgreSQL (Testcontainers). Constraints are verified with raw
 * INSERTs, so the tests prove that the database rejects invalid data on its own.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class LedgerTransactionRepositoryTest {

    private static final Currency TEST_CURRENCY = Currency.getInstance("XTS");

    @Autowired
    private LedgerTransactionRepository ledgerTransactionRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void persistsTransactionWithItsEntries() {
        Account alice = newAccount("Alice", AccountType.CUSTOMER);
        Account bob = newAccount("Bob", AccountType.MERCHANT);

        LedgerTransaction saved = ledgerTransactionRepository.saveAndFlush(
                LedgerTransaction.create("order:1:paid", List.of(
                        EntryDraft.debit(alice, 2_500),
                        EntryDraft.credit(bob, 2_500))));
        entityManager.clear();

        LedgerTransaction loaded = ledgerTransactionRepository.findById(saved.getId()).orElseThrow();

        assertThat(loaded.getReference()).isEqualTo("order:1:paid");
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getReversalOfId()).isNull();
        assertThat(loaded.getEntries())
                .extracting(e -> e.getAccount().getId(), LedgerEntry::getDirection, LedgerEntry::getAmount)
                .containsExactlyInAnyOrder(
                        tuple(alice.getId(), EntryDirection.DEBIT, 2_500L),
                        tuple(bob.getId(), EntryDirection.CREDIT, 2_500L));
    }

    @Test
    void findsTransactionByReference() {
        Account alice = newAccount("Alice", AccountType.CUSTOMER);
        Account bob = newAccount("Bob", AccountType.MERCHANT);
        ledgerTransactionRepository.saveAndFlush(LedgerTransaction.create("order:2:paid", List.of(
                EntryDraft.debit(alice, 100),
                EntryDraft.credit(bob, 100))));
        entityManager.clear();

        assertThat(ledgerTransactionRepository.findByReference("order:2:paid")).isPresent();
        assertThat(ledgerTransactionRepository.findByReference("order:999:paid")).isEmpty();
    }

    @Test
    void rejectsDuplicateReference() {
        Account alice = newAccount("Alice", AccountType.CUSTOMER);
        Account bob = newAccount("Bob", AccountType.MERCHANT);
        ledgerTransactionRepository.saveAndFlush(LedgerTransaction.create("order:3:paid", List.of(
                EntryDraft.debit(alice, 100),
                EntryDraft.credit(bob, 100))));

        assertThatThrownBy(() -> ledgerTransactionRepository.saveAndFlush(
                LedgerTransaction.create("order:3:paid", List.of(
                        EntryDraft.debit(alice, 100),
                        EntryDraft.credit(bob, 100)))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsBlankReference() {
        assertThatThrownBy(() -> insertTransaction("   ", null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsEntryWithNonPositiveAmount() {
        UUID transactionId = insertTransaction("tx-1", null);
        UUID accountId = newAccount("Alice", AccountType.CUSTOMER).getId();

        assertThatThrownBy(() -> insertEntry(transactionId, accountId, "DEBIT", 0))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsEntryWithUnknownDirection() {
        UUID transactionId = insertTransaction("tx-1", null);
        UUID accountId = newAccount("Alice", AccountType.CUSTOMER).getId();

        assertThatThrownBy(() -> insertEntry(transactionId, accountId, "SIDEWAYS", 100))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsEntryForMissingAccount() {
        UUID transactionId = insertTransaction("tx-1", null);

        assertThatThrownBy(() -> insertEntry(transactionId, UUID.randomUUID(), "DEBIT", 100))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsReversalOfMissingTransaction() {
        assertThatThrownBy(() -> insertTransaction("reversal-1", UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void allowsOnlyOneReversalPerTransaction() {
        UUID originalId = insertTransaction("original", null);
        insertTransaction("reversal-1", originalId);

        assertThatThrownBy(() -> insertTransaction("reversal-2", originalId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private Account newAccount(String name, AccountType type) {
        return accountRepository.saveAndFlush(Account.open(name, type, TEST_CURRENCY));
    }

    private UUID insertTransaction(String reference, UUID reversalOfId) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO ledger_transactions (reference, reversal_of_id) VALUES (?, ?) RETURNING id",
                UUID.class, reference, reversalOfId);
    }

    private void insertEntry(UUID transactionId, UUID accountId, String direction, long amount) {
        jdbcTemplate.update(
                "INSERT INTO ledger_entries (transaction_id, account_id, direction, amount) VALUES (?, ?, ?, ?)",
                transactionId, accountId, direction, amount);
    }
}