package dev.salvatore.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.salvatore.ledger.TestcontainersConfiguration;
import dev.salvatore.ledger.domain.account.Account;
import dev.salvatore.ledger.domain.account.AccountType;
import dev.salvatore.ledger.domain.exception.InsufficientFundsException;
import dev.salvatore.ledger.domain.exception.ReversalNotAllowedException;
import dev.salvatore.ledger.domain.exception.TransactionNotFoundException;
import dev.salvatore.ledger.domain.ledger.LedgerTransaction;
import dev.salvatore.ledger.repository.AccountRepository;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Not {@code @Transactional}, like the transfer integration test: the services commit for
 * real, ledger rows can never be cleaned up, so every test uses its own accounts and unique
 * references and never relies on table-wide counts.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ReversalServiceIntegrationTest {

    private static final Currency XTS = Currency.getInstance("XTS");

    @Autowired
    private TransferService transferService;

    @Autowired
    private ReversalService reversalService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void reversalRestoresBalancesAndAppendsMirroredEntries() {
        Account customer = fundedCustomer(10_000);
        Account merchant = newAccount(AccountType.MERCHANT);
        LedgerTransaction payment = transferService.transfer(
                uniqueReference("pay"), customer.getId(), merchant.getId(), 2_500);

        LedgerTransaction reversal = reversalService.reverse(payment.getId());

        assertThat(balanceOf(customer)).isEqualTo(10_000L);
        assertThat(balanceOf(merchant)).isZero();
        assertThat(reversal.getReference()).isEqualTo(ReversalService.referenceFor(payment.getId()));
        assertThat(reversalOfId(reversal.getId())).isEqualTo(payment.getId());
        assertThat(entryAmount(reversal.getId(), customer, "CREDIT")).isEqualTo(2_500L);
        assertThat(entryAmount(reversal.getId(), merchant, "DEBIT")).isEqualTo(2_500L);
        assertThat(ledgerSum(reversal.getId())).isZero();
        // History is untouched: the original entries are still there.
        assertThat(entryAmount(payment.getId(), customer, "DEBIT")).isEqualTo(2_500L);
        assertThat(entryAmount(payment.getId(), merchant, "CREDIT")).isEqualTo(2_500L);
    }

    @Test
    void reversalFailsWhenTheMoneyHasAlreadyBeenSpent() {
        Account customer = fundedCustomer(10_000);
        Account merchant = newAccount(AccountType.MERCHANT);
        Account supplier = newAccount(AccountType.CUSTOMER);
        LedgerTransaction payment = transferService.transfer(
                uniqueReference("pay"), customer.getId(), merchant.getId(), 2_500);
        transferService.transfer(uniqueReference("spend"), merchant.getId(), supplier.getId(), 2_500);

        assertThatThrownBy(() -> reversalService.reverse(payment.getId()))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(balanceOf(customer)).isEqualTo(7_500L);
        assertThat(balanceOf(merchant)).isZero();
        assertThat(balanceOf(supplier)).isEqualTo(2_500L);
        assertThat(countTransactions(ReversalService.referenceFor(payment.getId()))).isZero();
    }

    @Test
    void aTransactionCanBeReversedOnlyOnce() {
        Account customer = fundedCustomer(10_000);
        Account merchant = newAccount(AccountType.MERCHANT);
        LedgerTransaction payment = transferService.transfer(
                uniqueReference("pay"), customer.getId(), merchant.getId(), 2_500);
        reversalService.reverse(payment.getId());

        assertThatThrownBy(() -> reversalService.reverse(payment.getId()))
                .isInstanceOf(ReversalNotAllowedException.class);

        assertThat(balanceOf(customer)).isEqualTo(10_000L);
        assertThat(countTransactions(ReversalService.referenceFor(payment.getId()))).isEqualTo(1);
    }

    @Test
    void aReversalCannotBeReversed() {
        Account customer = fundedCustomer(10_000);
        Account merchant = newAccount(AccountType.MERCHANT);
        LedgerTransaction payment = transferService.transfer(
                uniqueReference("pay"), customer.getId(), merchant.getId(), 2_500);
        LedgerTransaction reversal = reversalService.reverse(payment.getId());

        assertThatThrownBy(() -> reversalService.reverse(reversal.getId()))
                .isInstanceOf(ReversalNotAllowedException.class);

        assertThat(balanceOf(customer)).isEqualTo(10_000L);
        assertThat(balanceOf(merchant)).isZero();
    }

    @Test
    void unknownTransactionIsRejected() {
        assertThatThrownBy(() -> reversalService.reverse(UUID.randomUUID()))
                .isInstanceOf(TransactionNotFoundException.class);
    }

    @Test
    void aFundingTransferFromTheExternalAccountCanBeReversed() {
        Account external = newAccount(AccountType.EXTERNAL);
        Account customer = newAccount(AccountType.CUSTOMER);
        LedgerTransaction funding = transferService.transfer(
                uniqueReference("fund"), external.getId(), customer.getId(), 5_000);

        reversalService.reverse(funding.getId());

        assertThat(balanceOf(external)).isZero();
        assertThat(balanceOf(customer)).isZero();
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

    private UUID reversalOfId(UUID transactionId) {
        return jdbc.queryForObject(
                "SELECT reversal_of_id FROM ledger_transactions WHERE id = ?", UUID.class, transactionId);
    }
}