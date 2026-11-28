package dev.salvatore.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.salvatore.ledger.TestcontainersConfiguration;
import dev.salvatore.ledger.domain.account.Account;
import dev.salvatore.ledger.domain.account.AccountType;
import dev.salvatore.ledger.domain.exception.CurrencyMismatchException;
import dev.salvatore.ledger.domain.exception.InsufficientFundsException;
import dev.salvatore.ledger.domain.exception.OrderNotFoundException;
import dev.salvatore.ledger.domain.exception.UnsupportedCurrencyException;
import dev.salvatore.ledger.domain.order.IllegalOrderTransitionException;
import dev.salvatore.ledger.domain.order.Order;
import dev.salvatore.ledger.domain.order.OrderStatus;
import dev.salvatore.ledger.repository.AccountRepository;
import dev.salvatore.ledger.support.LedgerInvariants;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Not {@code @Transactional}: the services commit for real. Orders are in EUR because EUR is the
 * only currency with a transit account; that account is shared by all tests, so the money is
 * checked as differences from the starting balance, and after each flow the system-wide
 * invariants are verified.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class OrderServiceIntegrationTest {

    private static final Currency EUR = Currency.getInstance("EUR");
    private static final Currency USD = Currency.getInstance("USD");
    private static final Currency XTS = Currency.getInstance("XTS");

    @Autowired
    private OrderService orderService;

    @Autowired
    private TransferService transferService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void anOrderIsCreatedInCreatedStatus() {
        Account customer = newAccount(AccountType.CUSTOMER, EUR);
        Account merchant = newAccount(AccountType.MERCHANT, EUR);

        Order order = orderService.create(customer.getId(), merchant.getId(), 2_500, EUR);

        assertThat(order.getId()).isNotNull();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CREATED);
        assertThat(statusInDb(order)).isEqualTo("CREATED");
    }

    @Test
    void creationRejectsAnOrderCurrencyDifferentFromTheAccounts() {
        Account customer = newAccount(AccountType.CUSTOMER, EUR);
        Account merchant = newAccount(AccountType.MERCHANT, EUR);

        assertThatThrownBy(() -> orderService.create(customer.getId(), merchant.getId(), 2_500, USD))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void creationRejectsACurrencyWithoutATransitAccount() {
        Account customer = newAccount(AccountType.CUSTOMER, XTS);
        Account merchant = newAccount(AccountType.MERCHANT, XTS);

        assertThatThrownBy(() -> orderService.create(customer.getId(), merchant.getId(), 2_500, XTS))
                .isInstanceOf(UnsupportedCurrencyException.class);
    }

    @Test
    void payingMovesTheMoneyIntoTheTransitAccount() {
        Account external = newAccount(AccountType.EXTERNAL, EUR);
        Account customer = fundedCustomer(external, 10_000);
        Account merchant = newAccount(AccountType.MERCHANT, EUR);
        Order order = orderService.create(customer.getId(), merchant.getId(), 2_500, EUR);
        long transitBefore = transitBalance();

        Order paid = orderService.pay(order.getId());

        assertThat(paid.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(statusInDb(order)).isEqualTo("PAID");
        assertThat(balanceOf(customer)).isEqualTo(7_500L);
        assertThat(transitBalance() - transitBefore).isEqualTo(2_500L);
        assertThat(countTransactions(paidReference(order))).isEqualTo(1);
        assertSystemInvariants();
    }

    @Test
    void payingTwiceIsANoOp() {
        Account external = newAccount(AccountType.EXTERNAL, EUR);
        Account customer = fundedCustomer(external, 10_000);
        Account merchant = newAccount(AccountType.MERCHANT, EUR);
        Order order = orderService.create(customer.getId(), merchant.getId(), 2_500, EUR);
        orderService.pay(order.getId());

        Order again = orderService.pay(order.getId());

        assertThat(again.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(balanceOf(customer)).isEqualTo(7_500L);
        assertThat(countTransactions(paidReference(order))).isEqualTo(1);
        assertSystemInvariants();
    }

    @Test
    void theFullLifecycleEndsWithTheMerchantPaid() {
        Account external = newAccount(AccountType.EXTERNAL, EUR);
        Account customer = fundedCustomer(external, 10_000);
        Account merchant = newAccount(AccountType.MERCHANT, EUR);
        Order order = orderService.create(customer.getId(), merchant.getId(), 2_500, EUR);
        long transitBefore = transitBalance();

        orderService.pay(order.getId());
        orderService.confirm(order.getId());
        Order consolidated = orderService.consolidate(order.getId());

        assertThat(consolidated.getStatus()).isEqualTo(OrderStatus.CONSOLIDATED);
        assertThat(balanceOf(customer)).isEqualTo(7_500L);
        assertThat(balanceOf(merchant)).isEqualTo(2_500L);
        assertThat(transitBalance() - transitBefore).isZero();
        assertThat(countTransactions(paidReference(order))).isEqualTo(1);
        assertThat(countTransactions(consolidatedReference(order))).isEqualTo(1);
        assertSystemInvariants();
    }

    @Test
    void cancellingAPaidOrderGivesTheMoneyBack() {
        Account external = newAccount(AccountType.EXTERNAL, EUR);
        Account customer = fundedCustomer(external, 10_000);
        Account merchant = newAccount(AccountType.MERCHANT, EUR);
        Order order = orderService.create(customer.getId(), merchant.getId(), 2_500, EUR);
        long transitBefore = transitBalance();
        orderService.pay(order.getId());

        Order cancelled = orderService.cancel(order.getId());

        assertThat(cancelled.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(balanceOf(customer)).isEqualTo(10_000L);
        assertThat(balanceOf(merchant)).isZero();
        assertThat(transitBalance() - transitBefore).isZero();
        assertThat(countTransactions(ReversalService.referenceFor(paymentTransactionId(order)))).isEqualTo(1);
        assertSystemInvariants();
    }

    @Test
    void cancellingAConfirmedOrderGivesTheMoneyBack() {
        Account external = newAccount(AccountType.EXTERNAL, EUR);
        Account customer = fundedCustomer(external, 10_000);
        Account merchant = newAccount(AccountType.MERCHANT, EUR);
        Order order = orderService.create(customer.getId(), merchant.getId(), 2_500, EUR);
        orderService.pay(order.getId());
        orderService.confirm(order.getId());

        orderService.cancel(order.getId());

        assertThat(statusInDb(order)).isEqualTo("CANCELLED");
        assertThat(balanceOf(customer)).isEqualTo(10_000L);
        assertSystemInvariants();
    }

    @Test
    void cancellingACreatedOrderMovesNoMoney() {
        Account external = newAccount(AccountType.EXTERNAL, EUR);
        Account customer = fundedCustomer(external, 10_000);
        Account merchant = newAccount(AccountType.MERCHANT, EUR);
        Order order = orderService.create(customer.getId(), merchant.getId(), 2_500, EUR);

        orderService.cancel(order.getId());

        assertThat(statusInDb(order)).isEqualTo("CANCELLED");
        assertThat(balanceOf(customer)).isEqualTo(10_000L);
        assertThat(countTransactions(paidReference(order))).isZero();
        assertSystemInvariants();
    }

    @Test
    void aConsolidatedOrderCannotBeCancelled() {
        Account external = newAccount(AccountType.EXTERNAL, EUR);
        Account customer = fundedCustomer(external, 10_000);
        Account merchant = newAccount(AccountType.MERCHANT, EUR);
        Order order = orderService.create(customer.getId(), merchant.getId(), 2_500, EUR);
        orderService.pay(order.getId());
        orderService.confirm(order.getId());
        orderService.consolidate(order.getId());

        assertThatThrownBy(() -> orderService.cancel(order.getId()))
                .isInstanceOf(IllegalOrderTransitionException.class);

        assertThat(statusInDb(order)).isEqualTo("CONSOLIDATED");
        assertThat(balanceOf(merchant)).isEqualTo(2_500L);
        assertSystemInvariants();
    }

    @Test
    void cancellingTwiceReversesThePaymentOnlyOnce() {
        Account external = newAccount(AccountType.EXTERNAL, EUR);
        Account customer = fundedCustomer(external, 10_000);
        Account merchant = newAccount(AccountType.MERCHANT, EUR);
        Order order = orderService.create(customer.getId(), merchant.getId(), 2_500, EUR);
        orderService.pay(order.getId());
        orderService.cancel(order.getId());

        Order again = orderService.cancel(order.getId());

        assertThat(again.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(balanceOf(customer)).isEqualTo(10_000L);
        assertThat(countTransactions(ReversalService.referenceFor(paymentTransactionId(order)))).isEqualTo(1);
        assertSystemInvariants();
    }

    @Test
    void aFailedPaymentLeavesTheOrderInCreatedStatus() {
        Account external = newAccount(AccountType.EXTERNAL, EUR);
        Account customer = fundedCustomer(external, 100);
        Account merchant = newAccount(AccountType.MERCHANT, EUR);
        Order order = orderService.create(customer.getId(), merchant.getId(), 2_500, EUR);

        assertThatThrownBy(() -> orderService.pay(order.getId()))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(statusInDb(order)).isEqualTo("CREATED");
        assertThat(versionInDb(order)).isZero();
        assertThat(balanceOf(customer)).isEqualTo(100L);
        assertSystemInvariants();
    }

    @Test
    void anUnknownOrderIsRejected() {
        assertThatThrownBy(() -> orderService.pay(UUID.randomUUID()))
                .isInstanceOf(OrderNotFoundException.class);
    }

    private Account newAccount(AccountType type, Currency currency) {
        return accountRepository.save(Account.open("ord-" + UUID.randomUUID(), type, currency));
    }

    /** Creates a customer funded from the given EXTERNAL account, like a deposit would. */
    private Account fundedCustomer(Account external, long amount) {
        Account customer = newAccount(AccountType.CUSTOMER, EUR);
        transferService.transfer("it:fund:" + UUID.randomUUID(), external.getId(), customer.getId(), amount);
        return customer;
    }

    private void assertSystemInvariants() {
        LedgerInvariants.assertCurrencyIsBalanced(jdbc, "EUR");
        LedgerInvariants.assertTransitMatchesOpenOrders(jdbc, "EUR");
    }

    private static String paidReference(Order order) {
        return "order:%s:paid".formatted(order.getId());
    }

    private static String consolidatedReference(Order order) {
        return "order:%s:consolidated".formatted(order.getId());
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

    private UUID paymentTransactionId(Order order) {
        return jdbc.queryForObject(
                "SELECT id FROM ledger_transactions WHERE reference = ?", UUID.class, paidReference(order));
    }
}