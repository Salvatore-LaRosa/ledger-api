package dev.salvatore.ledger.service;

import static dev.salvatore.ledger.support.ConcurrentRunner.runAtOnce;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.salvatore.ledger.TestcontainersConfiguration;
import dev.salvatore.ledger.domain.account.Account;
import dev.salvatore.ledger.domain.account.AccountType;
import dev.salvatore.ledger.domain.exception.AccountNotFoundException;
import dev.salvatore.ledger.domain.exception.IdempotencyKeyReusedException;
import dev.salvatore.ledger.domain.exception.InsufficientFundsException;
import dev.salvatore.ledger.repository.AccountRepository;
import dev.salvatore.ledger.support.ConcurrentRunner.Outcome;
import dev.salvatore.ledger.support.LedgerInvariants;
import java.util.ArrayList;
import java.util.Collections;
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
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Not {@code @Transactional}: the service commits for real, which is what idempotency under
 * concurrency is about. Data is committed and ledger rows can never be cleaned up, so every test
 * uses its own accounts and unique keys and never relies on table-wide counts.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class PaymentServiceIntegrationTest {

    private static final Currency XTS = Currency.getInstance("XTS");

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private TransferService transferService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void theFirstRequestMovesMoneyAndStoresThePayment() {
        Account external = newAccount(AccountType.EXTERNAL);
        Account customer = fundedAccount(external, 10_000);
        Account merchant = newAccount(AccountType.MERCHANT);
        String key = uniqueKey();

        PaymentResult result = paymentService.pay(key, command(customer, merchant, 2_500));

        assertThat(result.replayed()).isFalse();
        assertThat(result.payment().getIdempotencyKey()).isEqualTo(key);
        assertThat(result.payment().getAmount()).isEqualTo(2_500L);
        assertThat(balanceOf(customer)).isEqualTo(7_500L);
        assertThat(balanceOf(merchant)).isEqualTo(2_500L);
        assertThat(countPayments(key)).isEqualTo(1);
        assertThat(countTransactions("payment:" + key)).isEqualTo(1);
        LedgerInvariants.assertHold(jdbc, ids(external, customer, merchant));
    }

    @Test
    void aRepeatedRequestReturnsTheSamePaymentAndMovesNoMoreMoney() {
        Account external = newAccount(AccountType.EXTERNAL);
        Account customer = fundedAccount(external, 10_000);
        Account merchant = newAccount(AccountType.MERCHANT);
        String key = uniqueKey();
        PaymentResult first = paymentService.pay(key, command(customer, merchant, 2_500));

        PaymentResult second = paymentService.pay(key, command(customer, merchant, 2_500));

        assertThat(second.replayed()).isTrue();
        assertThat(second.payment().getId()).isEqualTo(first.payment().getId());
        assertThat(balanceOf(customer)).isEqualTo(7_500L);
        assertThat(balanceOf(merchant)).isEqualTo(2_500L);
        assertThat(countTransactions("payment:" + key)).isEqualTo(1);
    }

    @Test
    void theSameKeyWithADifferentAmountIsRejected() {
        Account external = newAccount(AccountType.EXTERNAL);
        Account customer = fundedAccount(external, 10_000);
        Account merchant = newAccount(AccountType.MERCHANT);
        String key = uniqueKey();
        paymentService.pay(key, command(customer, merchant, 2_500));

        assertThatThrownBy(() -> paymentService.pay(key, command(customer, merchant, 3_000)))
                .isInstanceOf(IdempotencyKeyReusedException.class);

        assertThat(balanceOf(customer)).isEqualTo(7_500L);
        assertThat(balanceOf(merchant)).isEqualTo(2_500L);
    }

    @Test
    void aFailedPaymentDoesNotConsumeTheKey() {
        Account external = newAccount(AccountType.EXTERNAL);
        Account customer = fundedAccount(external, 100);
        Account merchant = newAccount(AccountType.MERCHANT);
        String key = uniqueKey();

        assertThatThrownBy(() -> paymentService.pay(key, command(customer, merchant, 5_000)))
                .isInstanceOf(InsufficientFundsException.class);
        assertThat(countPayments(key)).isZero();

        transferService.transfer("it:topup:" + UUID.randomUUID(), external.getId(), customer.getId(), 10_000);
        PaymentResult retry = paymentService.pay(key, command(customer, merchant, 5_000));

        assertThat(retry.replayed()).isFalse();
        assertThat(balanceOf(merchant)).isEqualTo(5_000L);
        assertThat(countPayments(key)).isEqualTo(1);
    }

    @Test
    void differentKeysAreIndependentPayments() {
        Account external = newAccount(AccountType.EXTERNAL);
        Account customer = fundedAccount(external, 10_000);
        Account merchant = newAccount(AccountType.MERCHANT);

        paymentService.pay(uniqueKey(), command(customer, merchant, 1_000));
        paymentService.pay(uniqueKey(), command(customer, merchant, 1_000));

        assertThat(balanceOf(customer)).isEqualTo(8_000L);
        assertThat(balanceOf(merchant)).isEqualTo(2_000L);
    }

    @Test
    void anUnknownAccountIsRejectedAndNothingIsStored() {
        Account external = newAccount(AccountType.EXTERNAL);
        Account customer = fundedAccount(external, 1_000);
        String key = uniqueKey();

        assertThatThrownBy(() -> paymentService.pay(key,
                new PaymentCommand(customer.getId(), UUID.randomUUID(), 100)))
                .isInstanceOf(AccountNotFoundException.class);

        assertThat(countPayments(key)).isZero();
        assertThat(balanceOf(customer)).isEqualTo(1_000L);
    }

    @Test
    void concurrentDuplicateRequestsMoveTheMoneyExactlyOnce() throws Exception {
        Account external = newAccount(AccountType.EXTERNAL);
        Account customer = fundedAccount(external, 10_000);
        Account merchant = newAccount(AccountType.MERCHANT);
        String key = uniqueKey();
        PaymentCommand command = command(customer, merchant, 2_500);
        List<PaymentResult> results = Collections.synchronizedList(new ArrayList<>());

        List<Runnable> tasks = new ArrayList<>();
        for (int worker = 0; worker < 8; worker++) {
            tasks.add(() -> results.add(paymentService.pay(key, command)));
        }
        List<Outcome> outcomes = runAtOnce(tasks);

        assertThat(failures(outcomes)).isEmpty();
        // Everyone gets an answer: one request executed, the others were replays of it.
        assertThat(results).hasSize(8);
        assertThat(results.stream().filter(result -> !result.replayed()).count()).isEqualTo(1L);
        assertThat(results.stream().map(result -> result.payment().getId()).distinct().count()).isEqualTo(1L);
        assertThat(balanceOf(customer)).isEqualTo(7_500L);
        assertThat(balanceOf(merchant)).isEqualTo(2_500L);
        assertThat(countPayments(key)).isEqualTo(1);
        assertThat(countTransactions("payment:" + key)).isEqualTo(1);
        LedgerInvariants.assertHold(jdbc, ids(external, customer, merchant));
    }

    @Test
    void concurrentRequestsWithTheSameKeyButDifferentAmountsLetOnlyOneWin() throws Exception {
        Account external = newAccount(AccountType.EXTERNAL);
        Account customer = fundedAccount(external, 10_000);
        Account merchant = newAccount(AccountType.MERCHANT);
        String key = uniqueKey();
        List<PaymentResult> results = Collections.synchronizedList(new ArrayList<>());

        List<Runnable> tasks = new ArrayList<>();
        for (int worker = 0; worker < 8; worker++) {
            PaymentCommand command = command(customer, merchant, 100 + worker);
            tasks.add(() -> results.add(paymentService.pay(key, command)));
        }
        List<Outcome> outcomes = runAtOnce(tasks);

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().replayed()).isFalse();
        assertThat(failures(outcomes))
                .hasSize(7)
                .allSatisfy(failure -> assertThat(failure).isInstanceOf(IdempotencyKeyReusedException.class));
        long winningAmount = results.getFirst().payment().getAmount();
        assertThat(balanceOf(merchant)).isEqualTo(winningAmount);
        assertThat(balanceOf(customer)).isEqualTo(10_000L - winningAmount);
        assertThat(countPayments(key)).isEqualTo(1);
        LedgerInvariants.assertHold(jdbc, ids(external, customer, merchant));
    }

    private static PaymentCommand command(Account source, Account destination, long amount) {
        return new PaymentCommand(source.getId(), destination.getId(), amount);
    }

    private Account newAccount(AccountType type) {
        return accountRepository.save(Account.open("pay-" + UUID.randomUUID(), type, XTS));
    }

    /** Creates a customer account funded from the given EXTERNAL account, like a deposit would. */
    private Account fundedAccount(Account external, long amount) {
        Account account = newAccount(AccountType.CUSTOMER);
        transferService.transfer("it:fund:" + UUID.randomUUID(), external.getId(), account.getId(), amount);
        return account;
    }

    private static String uniqueKey() {
        return "it-" + UUID.randomUUID();
    }

    private static List<Throwable> failures(List<Outcome> outcomes) {
        return outcomes.stream().map(Outcome::failure).filter(Objects::nonNull).toList();
    }

    private static List<UUID> ids(Account... accounts) {
        return java.util.Arrays.stream(accounts).map(Account::getId).toList();
    }

    // Reads go through JDBC, not JPA, so they see exactly what is in the database.

    private long balanceOf(Account account) {
        return jdbc.queryForObject(
                "SELECT balance FROM accounts WHERE id = ?", Long.class, account.getId());
    }

    private int countPayments(String key) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM payments WHERE idempotency_key = ?", Integer.class, key);
    }

    private int countTransactions(String reference) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM ledger_transactions WHERE reference = ?", Integer.class, reference);
    }
}