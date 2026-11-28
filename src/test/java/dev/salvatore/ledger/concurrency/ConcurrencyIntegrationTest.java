package dev.salvatore.ledger.concurrency;

import static dev.salvatore.ledger.support.ConcurrentRunner.runAtOnce;
import static org.assertj.core.api.Assertions.assertThat;

import dev.salvatore.ledger.TestcontainersConfiguration;
import dev.salvatore.ledger.domain.account.Account;
import dev.salvatore.ledger.domain.account.AccountType;
import dev.salvatore.ledger.domain.exception.InsufficientFundsException;
import dev.salvatore.ledger.domain.exception.ReversalNotAllowedException;
import dev.salvatore.ledger.domain.ledger.LedgerTransaction;
import dev.salvatore.ledger.repository.AccountRepository;
import dev.salvatore.ledger.service.ReversalService;
import dev.salvatore.ledger.service.TransferService;
import dev.salvatore.ledger.support.ConcurrentRunner.Outcome;
import dev.salvatore.ledger.support.LedgerInvariants;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Real threads against a real PostgreSQL. Not {@code @Transactional}: each service call opens
 * and commits its own transaction, exactly as in production. Ledger rows can never be cleaned
 * up, so every test creates its own accounts and unique references.
 *
 * <p>Passing does not prove the absence of races, it makes hiding one very unlikely. That is
 * why the assertions are deterministic counts and invariants, never timings.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class ConcurrencyIntegrationTest {

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
    void oppositeTransfersBetweenTwoAccountsDoNotDeadlock() throws Exception {
        Account external = newAccount(AccountType.EXTERNAL);
        Account a = fundedAccount(external, 1_000_000);
        Account b = fundedAccount(external, 1_000_000);
        String run = runId();

        List<Runnable> tasks = new ArrayList<>();
        for (int worker = 0; worker < 4; worker++) {
            tasks.add(transfers(run, "ab" + worker, a, b, 25, 10));
            tasks.add(transfers(run, "ba" + worker, b, a, 25, 10));
        }
        List<Outcome> outcomes = runAtOnce(tasks);

        assertThat(failures(outcomes)).isEmpty();
        // Same number of transfers in each direction: balances are back where they started.
        assertThat(balanceOf(a)).isEqualTo(1_000_000L);
        assertThat(balanceOf(b)).isEqualTo(1_000_000L);
        LedgerInvariants.assertHold(jdbc, ids(List.of(external, a, b)));
    }

    @Test
    void randomTransfersAmongManyAccountsKeepTheLedgerConsistent() throws Exception {
        Account external = newAccount(AccountType.EXTERNAL);
        List<Account> accounts = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            accounts.add(fundedAccount(external, 50_000));
        }
        String run = runId();

        List<Runnable> tasks = new ArrayList<>();
        for (int worker = 0; worker < 8; worker++) {
            tasks.add(randomTransfers(run, worker, accounts, 60));
        }
        List<Outcome> outcomes = runAtOnce(tasks);

        assertThat(failures(outcomes)).isEmpty();
        // Money only moved between these accounts: their total cannot have changed.
        assertThat(accounts.stream().mapToLong(this::balanceOf).sum()).isEqualTo(250_000L);
        List<Account> group = new ArrayList<>(accounts);
        group.add(external);
        LedgerInvariants.assertHold(jdbc, ids(group));
    }

    @Test
    void concurrentTransfersFromTheSameAccountNeverOverdrawIt() throws Exception {
        Account external = newAccount(AccountType.EXTERNAL);
        Account source = fundedAccount(external, 500);
        Account merchant = newAccount(AccountType.MERCHANT);
        String run = runId();

        // Ten transfers of 100 from an account holding 500: exactly five can succeed.
        List<Runnable> tasks = new ArrayList<>();
        for (int worker = 0; worker < 10; worker++) {
            tasks.add(transfers(run, "w" + worker, source, merchant, 1, 100));
        }
        List<Outcome> outcomes = runAtOnce(tasks);

        assertThat(outcomes.stream().filter(Outcome::succeeded).count()).isEqualTo(5L);
        assertThat(failures(outcomes))
                .hasSize(5)
                .allSatisfy(failure -> assertThat(failure).isInstanceOf(InsufficientFundsException.class));
        assertThat(balanceOf(source)).isZero();
        assertThat(balanceOf(merchant)).isEqualTo(500L);
        LedgerInvariants.assertHold(jdbc, ids(List.of(external, source, merchant)));
    }

    @Test
    void concurrentReversalsOfTheSameTransactionHappenExactlyOnce() throws Exception {
        Account external = newAccount(AccountType.EXTERNAL);
        Account customer = fundedAccount(external, 10_000);
        Account merchant = newAccount(AccountType.MERCHANT);
        LedgerTransaction payment = transferService.transfer(
                "conc:" + runId() + ":pay", customer.getId(), merchant.getId(), 2_500);

        List<Runnable> tasks = new ArrayList<>();
        for (int worker = 0; worker < 8; worker++) {
            tasks.add(() -> reversalService.reverse(payment.getId()));
        }
        List<Outcome> outcomes = runAtOnce(tasks);

        assertThat(outcomes.stream().filter(Outcome::succeeded).count()).isEqualTo(1L);
        assertThat(failures(outcomes))
                .hasSize(7)
                .allSatisfy(failure -> assertThat(failure).isInstanceOf(ReversalNotAllowedException.class));
        assertThat(balanceOf(customer)).isEqualTo(10_000L);
        assertThat(balanceOf(merchant)).isZero();
        assertThat(countTransactions(ReversalService.referenceFor(payment.getId()))).isEqualTo(1);
        LedgerInvariants.assertHold(jdbc, ids(List.of(external, customer, merchant)));
    }

    @Test
    void transfersAndReversalsInOppositeDirectionsDoNotDeadlock() throws Exception {
        Account external = newAccount(AccountType.EXTERNAL);
        Account a = fundedAccount(external, 1_000_000);
        Account b = fundedAccount(external, 1_000_000);
        String run = runId();
        List<LedgerTransaction> payments = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            payments.add(transferService.transfer("conc:%s:pay:%d".formatted(run, i), a.getId(), b.getId(), 10));
        }

        // Reversals move money from b to a while new transfers move it from a to b.
        List<Runnable> tasks = new ArrayList<>();
        for (int worker = 0; worker < 4; worker++) {
            List<LedgerTransaction> mine = payments.subList(worker * 5, worker * 5 + 5);
            tasks.add(() -> mine.forEach(payment -> reversalService.reverse(payment.getId())));
            tasks.add(transfers(run, "new" + worker, a, b, 5, 10));
        }
        List<Outcome> outcomes = runAtOnce(tasks);

        assertThat(failures(outcomes)).isEmpty();
        // a: -200 (payments) +200 (reversals) -200 (new transfers); b is the mirror image.
        assertThat(balanceOf(a)).isEqualTo(999_800L);
        assertThat(balanceOf(b)).isEqualTo(1_000_200L);
        LedgerInvariants.assertHold(jdbc, ids(List.of(external, a, b)));
    }

    private Runnable transfers(String run, String label, Account from, Account to, int count, long amount) {
        return () -> {
            for (int i = 0; i < count; i++) {
                transferService.transfer(
                        "conc:%s:%s:%d".formatted(run, label, i), from.getId(), to.getId(), amount);
            }
        };
    }

    private Runnable randomTransfers(String run, int worker, List<Account> accounts, int count) {
        return () -> {
            Random random = new Random(worker);
            for (int i = 0; i < count; i++) {
                int from = random.nextInt(accounts.size());
                int to = (from + 1 + random.nextInt(accounts.size() - 1)) % accounts.size();
                long amount = 1 + random.nextInt(50);
                transferService.transfer("conc:%s:r%d:%d".formatted(run, worker, i),
                        accounts.get(from).getId(), accounts.get(to).getId(), amount);
            }
        };
    }

    private Account newAccount(AccountType type) {
        return accountRepository.save(Account.open("conc-" + UUID.randomUUID(), type, XTS));
    }

    /** Creates a customer account funded from the given EXTERNAL account, like a deposit would. */
    private Account fundedAccount(Account external, long amount) {
        Account account = newAccount(AccountType.CUSTOMER);
        transferService.transfer("conc:fund:" + UUID.randomUUID(), external.getId(), account.getId(), amount);
        return account;
    }

    private static String runId() {
        return UUID.randomUUID().toString();
    }

    private static List<Throwable> failures(List<Outcome> outcomes) {
        return outcomes.stream().map(Outcome::failure).filter(Objects::nonNull).toList();
    }

    private static List<UUID> ids(Collection<Account> accounts) {
        return accounts.stream().map(Account::getId).toList();
    }

    private long balanceOf(Account account) {
        return jdbc.queryForObject(
                "SELECT balance FROM accounts WHERE id = ?", Long.class, account.getId());
    }

    private int countTransactions(String reference) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM ledger_transactions WHERE reference = ?", Integer.class, reference);
    }
}