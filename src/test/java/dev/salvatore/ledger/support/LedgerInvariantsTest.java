package dev.salvatore.ledger.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.salvatore.ledger.TestcontainersConfiguration;
import dev.salvatore.ledger.domain.account.Account;
import dev.salvatore.ledger.domain.account.AccountType;
import dev.salvatore.ledger.repository.AccountRepository;
import dev.salvatore.ledger.service.TransferService;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Proves the invariant checker can fail: a checker that never fails would make the
 * concurrency tests worthless. Data is committed and never cleaned up (ledger rows cannot be
 * deleted), so every test uses its own accounts.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LedgerInvariantsTest {

    private static final Currency XTS = Currency.getInstance("XTS");

    @Autowired
    private TransferService transferService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void holdOnAConsistentGroupOfAccounts() {
        Account external = newAccount(AccountType.EXTERNAL);
        Account customer = newAccount(AccountType.CUSTOMER);
        transferService.transfer("inv:" + UUID.randomUUID(), external.getId(), customer.getId(), 1_000);

        assertThatCode(() -> LedgerInvariants.assertHold(jdbc, List.of(external.getId(), customer.getId())))
                .doesNotThrowAnyException();
    }

    @Test
    void detectAnUnbalancedTransaction() {
        UUID first = insertAccount();
        UUID second = insertAccount();
        UUID transaction = UUID.randomUUID();
        jdbc.update("INSERT INTO ledger_transactions (id, reference) VALUES (?, ?)",
                transaction, "inv:" + transaction);
        insertCredit(transaction, first, 100);
        insertCredit(transaction, second, 100);

        assertThatThrownBy(() -> LedgerInvariants.assertHold(jdbc, List.of(first, second)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("do not sum to zero");
    }

    @Test
    void detectABalanceThatDoesNotMatchTheLedger() {
        Account external = newAccount(AccountType.EXTERNAL);
        Account customer = newAccount(AccountType.CUSTOMER);
        transferService.transfer("inv:" + UUID.randomUUID(), external.getId(), customer.getId(), 1_000);
        // Tampering with a balance is possible (accounts are not immutable): the ledger must reveal it.
        jdbc.update("UPDATE accounts SET balance = balance + 1 WHERE id = ?", customer.getId());

        assertThatThrownBy(() -> LedgerInvariants.assertHold(jdbc, List.of(external.getId(), customer.getId())))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("balance differs from the sum of their entries");
    }

    private Account newAccount(AccountType type) {
        return accountRepository.save(Account.open("inv-" + UUID.randomUUID(), type, XTS));
    }

    private UUID insertAccount() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO accounts (id, name, type, currency) VALUES (?, ?, 'CUSTOMER', 'XTS')",
                id, "inv-" + id);
        return id;
    }

    private void insertCredit(UUID transaction, UUID account, long amount) {
        jdbc.update("INSERT INTO ledger_entries (id, transaction_id, account_id, direction, amount) "
                + "VALUES (?, ?, ?, 'CREDIT', ?)", UUID.randomUUID(), transaction, account, amount);
    }
}
