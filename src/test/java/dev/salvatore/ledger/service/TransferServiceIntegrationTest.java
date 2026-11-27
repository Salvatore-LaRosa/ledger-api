package dev.salvatore.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.salvatore.ledger.TestcontainersConfiguration;
import dev.salvatore.ledger.domain.account.Account;
import dev.salvatore.ledger.domain.account.AccountType;
import dev.salvatore.ledger.domain.exception.AccountNotFoundException;
import dev.salvatore.ledger.domain.exception.CurrencyMismatchException;
import dev.salvatore.ledger.domain.exception.InsufficientFundsException;
import dev.salvatore.ledger.domain.ledger.LedgerTransaction;
import dev.salvatore.ledger.repository.AccountRepository;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Not {@code @Transactional} on purpose: the service opens and commits its own transaction,
 * and a test-managed one would hide what really reaches the database. Consequence: data is
 * committed and ledger rows can never be cleaned up, so every test creates its own accounts
 * and unique references and never relies on table-wide counts.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TransferServiceIntegrationTest {

    private static final Currency XTS = Currency.getInstance("XTS");
    private static final Currency XXX = Currency.getInstance("XXX");

    @Autowired
    private TransferService transferService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void transferMovesMoneyAndWritesBalancedLedgerEntries() {
        Account customer = fundedCustomer(10_000);
        Account merchant = newAccount(AccountType.MERCHANT);

        LedgerTransaction transaction = transferService.transfer(
                uniqueReference("pay"), customer.getId(), merchant.getId(), 2_500);

        assertThat(balanceOf(customer)).isEqualTo(7_500L);
        assertThat(balanceOf(merchant)).isEqualTo(2_500L);
        assertThat(entryAmount(transaction.getId(), customer, "DEBIT")).isEqualTo(2_500L);
        assertThat(entryAmount(transaction.getId(), merchant, "CREDIT")).isEqualTo(2_500L);
        assertThat(ledgerSum(transaction.getId())).isZero();
    }

    @Test
    void externalAccountCanFundOthersAndGoNegative() {
        Account external = newAccount(AccountType.EXTERNAL);
        Account customer = newAccount(AccountType.CUSTOMER);

        transferService.transfer(uniqueReference("fund"), external.getId(), customer.getId(), 5_000);

        assertThat(balanceOf(external)).isEqualTo(-5_000L);
        assertThat(balanceOf(customer)).isEqualTo(5_000L);
    }

    @Test
    void insufficientFundsChangesNothing() {
        Account customer = fundedCustomer(1_000);
        Account merchant = newAccount(AccountType.MERCHANT);
        String reference = uniqueReference("pay");

        assertThatThrownBy(() -> transferService.transfer(
                reference, customer.getId(), merchant.getId(), 5_000))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(balanceOf(customer)).isEqualTo(1_000L);
        assertThat(balanceOf(merchant)).isZero();
        assertThat(countTransactions(reference)).isZero();
    }

    @Test
    void transferBetweenDifferentCurrenciesIsRejected() {
        Account customer = newAccount(AccountType.CUSTOMER);
        Account otherCurrency = accountRepository.save(
                Account.open("it-" + UUID.randomUUID(), AccountType.MERCHANT, XXX));

        assertThatThrownBy(() -> transferService.transfer(
                uniqueReference("pay"), customer.getId(), otherCurrency.getId(), 100))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void duplicateReferenceIsRejectedAndChangesNothing() {
        Account customer = fundedCustomer(10_000);
        Account merchant = newAccount(AccountType.MERCHANT);
        String reference = uniqueReference("pay");
        transferService.transfer(reference, customer.getId(), merchant.getId(), 1_000);

        assertThatThrownBy(() -> transferService.transfer(
                reference, customer.getId(), merchant.getId(), 1_000))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(balanceOf(customer)).isEqualTo(9_000L);
        assertThat(balanceOf(merchant)).isEqualTo(1_000L);
        assertThat(countTransactions(reference)).isEqualTo(1);
    }

    @Test
    void unknownAccountIsRejected() {
        Account customer = fundedCustomer(1_000);

        assertThatThrownBy(() -> transferService.transfer(
                uniqueReference("pay"), customer.getId(), UUID.randomUUID(), 100))
                .isInstanceOf(AccountNotFoundException.class);

        assertThat(balanceOf(customer)).isEqualTo(1_000L);
    }

    private Account newAccount(AccountType type) {
        return accountRepository.save(Account.open("it-" + UUID.randomUUID(), type, XTS));
    }

    /** Funds a customer from a fresh EXTERNAL account, like a deposit would. */
    private Account fundedCustomer(long amount) {
        Account external = newAccount(AccountType.EXTERNAL);
        Account customer = newAccount(AccountType.CUSTOMER);
        transferService.transfer(uniqueReference("fund"), external.getId(), customer.getId(), amount);
        return customer;
    }

    private static String uniqueReference(String prefix) {
        return "it:" + prefix + ":" + UUID.randomUUID();
    }

    // Reads go through JDBC, not JPA, so they see exactly what is in the database.

    private long balanceOf(Account account) {
        return jdbc.queryForObject(
                "SELECT balance FROM accounts WHERE id = ?", Long.class, account.getId());
    }

    private long entryAmount(UUID transactionId, Account account, String direction) {
        return jdbc.queryForObject(
                "SELECT amount FROM ledger_entries WHERE transaction_id = ? AND account_id = ? AND direction = ?",
                Long.class, transactionId, account.getId(), direction);
    }

    private long ledgerSum(UUID transactionId) {
        return jdbc.queryForObject(
                "SELECT COALESCE(SUM(CASE direction WHEN 'CREDIT' THEN amount ELSE -amount END), 0) "
                        + "FROM ledger_entries WHERE transaction_id = ?",
                Long.class, transactionId);
    }

    private int countTransactions(String reference) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM ledger_transactions WHERE reference = ?", Integer.class, reference);
    }
}