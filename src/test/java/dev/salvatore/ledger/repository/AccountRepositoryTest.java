package dev.salvatore.ledger.repository;

import dev.salvatore.ledger.TestcontainersConfiguration;
import dev.salvatore.ledger.domain.account.Account;
import dev.salvatore.ledger.domain.account.AccountType;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs against a real PostgreSQL (Testcontainers). Constraints are tested with raw
 * Inserts so that we verify the database itself, not just the Java code.
 * <p>
 * Test currencies (XTS, XXX) are used because a later migration seeds the EUR transit account.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class AccountRepositoryTest {

    private static final Currency TEST_CURRENCY = Currency.getInstance("XTS");

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void persistsAccountWithGeneratedIdAndZeroBalance() {
        Account saved = accountRepository.saveAndFlush(
                Account.open("Alice", AccountType.CUSTOMER, TEST_CURRENCY));
        entityManager.clear();

        Account loaded = accountRepository.findById(saved.getId()).orElseThrow();

        assertThat(loaded.getId()).isNotNull();
        assertThat(loaded.getName()).isEqualTo("Alice");
        assertThat(loaded.getType()).isEqualTo(AccountType.CUSTOMER);
        assertThat(loaded.getCurrency()).isEqualTo(TEST_CURRENCY);
        assertThat(loaded.getBalance()).isZero();
        assertThat(loaded.getCreatedAt()).isNotNull();
    }

    @Test
    void rejectsNegativeBalanceForCustomer() {
        assertThatThrownBy(() -> insertAccount("CUSTOMER", "XTS", -1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void allowsNegativeBalanceForExternalAccount() {
        insertAccount("EXTERNAL", "XTS", -100);

        Long balance = jdbcTemplate.queryForObject(
                "SELECT balance FROM accounts WHERE name = ?", Long.class, "test-EXTERNAL");
        assertThat(balance).isEqualTo(-100L);
    }

    @Test
    void rejectsMalformedCurrency() {
        assertThatThrownBy(() -> insertAccount("CUSTOMER", "eur", 0))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsUnknownAccountType() {
        assertThatThrownBy(() -> insertAccount("SAVINGS", "XTS", 0))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void allowsOnlyOneTransitAccountPerCurrency() {
        insertAccount("TRANSIT", "XTS", 0);

        assertThatThrownBy(() -> insertAccount("TRANSIT", "XTS", 0))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void allowsTransitAccountsInDifferentCurrencies() {
        insertAccount("TRANSIT", "XTS", 0);
        insertAccount("TRANSIT", "XXX", 0);

        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM accounts WHERE type = 'TRANSIT' AND currency IN ('XTS', 'XXX')",
                Integer.class);
        assertThat(count).isEqualTo(2);
    }

    private void insertAccount(String type, String currency, long balance) {
        jdbcTemplate.update(
                "INSERT INTO accounts (name, type, currency, balance) VALUES (?, ?, ?, ?)",
                "test-" + type, type, currency, balance);
    }
}
