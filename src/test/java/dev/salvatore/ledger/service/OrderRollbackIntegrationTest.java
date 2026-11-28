package dev.salvatore.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import dev.salvatore.ledger.TestcontainersConfiguration;
import dev.salvatore.ledger.domain.account.Account;
import dev.salvatore.ledger.domain.account.AccountType;
import dev.salvatore.ledger.domain.ledger.LedgerTransaction;
import dev.salvatore.ledger.domain.order.Order;
import dev.salvatore.ledger.repository.AccountRepository;
import dev.salvatore.ledger.repository.LedgerTransactionRepository;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves the atomicity of the order flows: a failure at any point undoes everything, the order
 * update, the balances and the ledger rows alike. The spy replaces a bean, so this class runs in
 * its own Spring context (and its own PostgreSQL container).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class OrderRollbackIntegrationTest {

    private static final Currency EUR = Currency.getInstance("EUR");

    @MockitoSpyBean
    private LedgerTransactionRepository ledgerTransactionRepository;

    @Autowired
    private OrderService orderService;

    @Autowired
    private TransferService transferService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void aFailureWritingTheLedgerRollsBackTheOrderStatus() {
        Fixture fixture = fixture();
        // The order update is flushed before the money moves: when the ledger write throws,
        // that update must be undone too.
        doThrow(new IllegalStateException("boom"))
                .when(ledgerTransactionRepository).saveAndFlush(any(LedgerTransaction.class));

        assertThatThrownBy(() -> orderService.pay(fixture.order().getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");

        assertThat(statusInDb(fixture.order())).isEqualTo("CREATED");
        assertThat(versionInDb(fixture.order())).isZero();
        assertThat(balanceOf(fixture.customer())).isEqualTo(10_000L);
        assertThat(countTransactions(paidReference(fixture.order()))).isZero();
    }

    @Test
    void aFailureAfterTheMoneyMovedRollsBackEverything() {
        Fixture fixture = fixture();
        long transitBefore = transitBalance();

        // pay() joins this transaction: its order update, ledger rows and balances are all
        // written (flushed) before the outer failure, and none of them may survive it.
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            orderService.pay(fixture.order().getId());
            throw new IllegalStateException("outer failure");
        })).isInstanceOf(IllegalStateException.class).hasMessage("outer failure");

        assertThat(statusInDb(fixture.order())).isEqualTo("CREATED");
        assertThat(balanceOf(fixture.customer())).isEqualTo(10_000L);
        assertThat(transitBalance()).isEqualTo(transitBefore);
        assertThat(countTransactions(paidReference(fixture.order()))).isZero();
    }

    @Test
    void aFailureReversingThePaymentRollsBackTheCancellation() {
        Fixture fixture = fixture();
        orderService.pay(fixture.order().getId());
        doThrow(new IllegalStateException("boom"))
                .when(ledgerTransactionRepository).saveAndFlush(any(LedgerTransaction.class));

        assertThatThrownBy(() -> orderService.cancel(fixture.order().getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");

        assertThat(statusInDb(fixture.order())).isEqualTo("PAID");
        assertThat(versionInDb(fixture.order())).isEqualTo(1L);
        assertThat(balanceOf(fixture.customer())).isEqualTo(7_500L);
        UUID paymentTransaction = jdbc.queryForObject(
                "SELECT id FROM ledger_transactions WHERE reference = ?", UUID.class, paidReference(fixture.order()));
        assertThat(countTransactions(ReversalService.referenceFor(paymentTransaction))).isZero();
    }

    private record Fixture(Account customer, Order order) {
    }

    /** A customer holding 10,000 and a CREATED order of 2,500, set up before any failure is armed. */
    private Fixture fixture() {
        Account external = newAccount(AccountType.EXTERNAL);
        Account customer = newAccount(AccountType.CUSTOMER);
        Account merchant = newAccount(AccountType.MERCHANT);
        transferService.transfer("rb:fund:" + UUID.randomUUID(), external.getId(), customer.getId(), 10_000);
        Order order = orderService.create(customer.getId(), merchant.getId(), 2_500, EUR);
        return new Fixture(customer, order);
    }

    private Account newAccount(AccountType type) {
        return accountRepository.save(Account.open("rb-" + UUID.randomUUID(), type, EUR));
    }

    private static String paidReference(Order order) {
        return "order:%s:paid".formatted(order.getId());
    }

    // Reads go through JDBC, not JPA, so they see exactly what is in the database.

    private long balanceOf(Account account) {
        return jdbc.queryForObject(
                "SELECT balance FROM accounts WHERE id = ?", Long.class, account.getId());
    }

    private long transitBalance() {
        return jdbc.queryForObject(
                "SELECT balance FROM accounts WHERE type = 'TRANSIT' AND currency = 'EUR'", Long.class);
    }

    private String statusInDb(Order order) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, order.getId());
    }

    private long versionInDb(Order order) {
        return jdbc.queryForObject("SELECT version FROM orders WHERE id = ?", Long.class, order.getId());
    }

    private int countTransactions(String reference) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM ledger_transactions WHERE reference = ?", Integer.class, reference);
    }
}