package dev.salvatore.ledger.concurrency;

import static dev.salvatore.ledger.support.ConcurrentRunner.runAtOnce;
import static org.assertj.core.api.Assertions.assertThat;

import dev.salvatore.ledger.TestcontainersConfiguration;
import dev.salvatore.ledger.domain.account.Account;
import dev.salvatore.ledger.domain.account.AccountType;
import dev.salvatore.ledger.domain.exception.InsufficientFundsException;
import dev.salvatore.ledger.domain.order.IllegalOrderTransitionException;
import dev.salvatore.ledger.domain.order.Order;
import dev.salvatore.ledger.repository.AccountRepository;
import dev.salvatore.ledger.service.OrderService;
import dev.salvatore.ledger.service.TransferService;
import dev.salvatore.ledger.support.ConcurrentRunner.Outcome;
import dev.salvatore.ledger.support.LedgerInvariants;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Real threads against a real PostgreSQL, on the order flows. As in the other concurrency tests
 * the assertions are deterministic counts and invariants, never timings.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class OrderConcurrencyIntegrationTest {

    private static final Currency EUR = Currency.getInstance("EUR");

    @Autowired
    private OrderService orderService;

    @Autowired
    private TransferService transferService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void concurrentPaymentsOfTheSameOrderMoveTheMoneyOnce() throws Exception {
        Account external = newAccount(AccountType.EXTERNAL);
        Account customer = fundedCustomer(external, 10_000);
        Account merchant = newAccount(AccountType.MERCHANT);
        Order order = orderService.create(customer.getId(), merchant.getId(), 2_500, EUR);
        long transitBefore = transitBalance();

        List<Runnable> tasks = new ArrayList<>();
        for (int worker = 0; worker < 8; worker++) {
            tasks.add(() -> orderService.pay(order.getId()));
        }
        List<Outcome> outcomes = runAtOnce(tasks);

        // A request that arrives after the winner committed is an idempotent no-op (success);
        // one that raced it loses on the optimistic lock. Nothing else is acceptable.
        assertThat(outcomes.stream().filter(Outcome::succeeded).count()).isGreaterThanOrEqualTo(1L);
        assertThat(failures(outcomes))
                .allSatisfy(failure -> assertThat(failure).isInstanceOf(OptimisticLockingFailureException.class));
        assertThat(statusInDb(order)).isEqualTo("PAID");
        assertThat(balanceOf(customer)).isEqualTo(7_500L);
        assertThat(transitBalance() - transitBefore).isEqualTo(2_500L);
        assertThat(countTransactions("order:%s:paid".formatted(order.getId()))).isEqualTo(1);
        assertSystemInvariants();
    }

    @Test
    void concurrentPayAndCancelOnTheSameOrderLeaveTheLedgerConsistentWithTheStatus() throws Exception {
        Account external = newAccount(AccountType.EXTERNAL);
        Account merchant = newAccount(AccountType.MERCHANT);
        List<Account> customers = new ArrayList<>();
        List<Order> orders = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Account customer = fundedCustomer(external, 1_000);
            customers.add(customer);
            orders.add(orderService.create(customer.getId(), merchant.getId(), 400, EUR));
        }

        List<Runnable> tasks = new ArrayList<>();
        for (Order order : orders) {
            tasks.add(() -> orderService.pay(order.getId()));
            tasks.add(() -> orderService.cancel(order.getId()));
        }
        List<Outcome> outcomes = runAtOnce(tasks);

        assertThat(failures(outcomes)).allSatisfy(failure -> assertThat(failure)
                .isInstanceOfAny(OptimisticLockingFailureException.class, IllegalOrderTransitionException.class));
        for (int i = 0; i < orders.size(); i++) {
            String status = statusInDb(orders.get(i));
            // The money must agree with the final status: never PAID with the money back,
            // never CANCELLED with the money still in transit.
            assertThat(status).isIn("PAID", "CANCELLED");
            long expectedBalance = status.equals("PAID") ? 600L : 1_000L;
            assertThat(balanceOf(customers.get(i))).isEqualTo(expectedBalance);
        }
        assertSystemInvariants();
    }

    @Test
    void concurrentPaymentsOfDifferentOrdersFromTheSameBuyerNeverOverdraw() throws Exception {
        Account external = newAccount(AccountType.EXTERNAL);
        Account customer = fundedCustomer(external, 500);
        Account merchant = newAccount(AccountType.MERCHANT);
        List<Order> orders = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            orders.add(orderService.create(customer.getId(), merchant.getId(), 100, EUR));
        }
        long transitBefore = transitBalance();

        List<Runnable> tasks = new ArrayList<>();
        for (Order order : orders) {
            tasks.add(() -> orderService.pay(order.getId()));
        }
        List<Outcome> outcomes = runAtOnce(tasks);

        // Ten orders of 100 against a balance of 500: exactly five can be paid.
        assertThat(outcomes.stream().filter(Outcome::succeeded).count()).isEqualTo(5L);
        assertThat(failures(outcomes))
                .hasSize(5)
                .allSatisfy(failure -> assertThat(failure).isInstanceOf(InsufficientFundsException.class));
        assertThat(balanceOf(customer)).isZero();
        assertThat(transitBalance() - transitBefore).isEqualTo(500L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM orders WHERE buyer_account_id = ? AND status = 'PAID'",
                Integer.class, customer.getId())).isEqualTo(5);
        assertSystemInvariants();
    }

    private Account newAccount(AccountType type) {
        return accountRepository.save(Account.open("oc-" + UUID.randomUUID(), type, EUR));
    }

    private Account fundedCustomer(Account external, long amount) {
        Account customer = newAccount(AccountType.CUSTOMER);
        transferService.transfer("conc:fund:" + UUID.randomUUID(), external.getId(), customer.getId(), amount);
        return customer;
    }

    private void assertSystemInvariants() {
        LedgerInvariants.assertCurrencyIsBalanced(jdbc, "EUR");
        LedgerInvariants.assertTransitMatchesOpenOrders(jdbc, "EUR");
    }

    private static List<Throwable> failures(List<Outcome> outcomes) {
        return outcomes.stream().map(Outcome::failure).filter(Objects::nonNull).toList();
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

    private int countTransactions(String reference) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM ledger_transactions WHERE reference = ?", Integer.class, reference);
    }
}