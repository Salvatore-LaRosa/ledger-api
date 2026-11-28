package dev.salvatore.ledger.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.salvatore.ledger.TestcontainersConfiguration;
import dev.salvatore.ledger.domain.account.Account;
import dev.salvatore.ledger.domain.account.AccountType;
import dev.salvatore.ledger.domain.payment.Payment;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class PaymentRepositoryTest {

    private static final Currency XTS = Currency.getInstance("XTS");
    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private Account source;
    private Account destination;

    @BeforeEach
    void createAccounts() {
        source = accountRepository.saveAndFlush(Account.open("payer", AccountType.CUSTOMER, XTS));
        destination = accountRepository.saveAndFlush(Account.open("payee", AccountType.MERCHANT, XTS));
    }

    @Test
    void insertIfAbsentInsertsTheFirstRequestOnly() {
        String key = uniqueKey();

        int first = paymentRepository.insertIfAbsent(key, HASH_A, source.getId(), destination.getId(), 100);
        int second = paymentRepository.insertIfAbsent(key, HASH_A, source.getId(), destination.getId(), 100);

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM payments WHERE idempotency_key = ?", Integer.class, key)).isEqualTo(1);
    }

    @Test
    void aSecondRequestWithTheSameKeyDoesNotOverwriteTheFirst() {
        String key = uniqueKey();
        paymentRepository.insertIfAbsent(key, HASH_A, source.getId(), destination.getId(), 100);

        int second = paymentRepository.insertIfAbsent(key, HASH_B, source.getId(), destination.getId(), 999);

        assertThat(second).isZero();
        Payment stored = paymentRepository.findByIdempotencyKey(key).orElseThrow();
        assertThat(stored.getRequestHash()).isEqualTo(HASH_A);
        assertThat(stored.getAmount()).isEqualTo(100L);
    }

    @Test
    void findByIdempotencyKeyMapsTheRow() {
        String key = uniqueKey();
        paymentRepository.insertIfAbsent(key, HASH_A, source.getId(), destination.getId(), 2_500);

        Payment payment = paymentRepository.findByIdempotencyKey(key).orElseThrow();

        assertThat(payment.getId()).isNotNull();
        assertThat(payment.getIdempotencyKey()).isEqualTo(key);
        assertThat(payment.getSourceAccountId()).isEqualTo(source.getId());
        assertThat(payment.getDestinationAccountId()).isEqualTo(destination.getId());
        assertThat(payment.getAmount()).isEqualTo(2_500L);
        assertThat(payment.getCreatedAt()).isNotNull();
    }

    // The constraint tests use direct INSERTs: they prove the database enforces the rules
    // even when the application is bypassed.

    @Test
    void zeroAmountIsRejectedByTheDatabase() {
        assertThatThrownBy(() -> insert(uniqueKey(), HASH_A, source.getId(), destination.getId(), 0))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_payments_amount_positive");
    }

    @Test
    void sameSourceAndDestinationIsRejectedByTheDatabase() {
        assertThatThrownBy(() -> insert(uniqueKey(), HASH_A, source.getId(), source.getId(), 100))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_payments_distinct_accounts");
    }

    @Test
    void malformedHashIsRejectedByTheDatabase() {
        assertThatThrownBy(() -> insert(uniqueKey(), "not-a-hash", source.getId(), destination.getId(), 100))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_payments_request_hash");
    }

    @Test
    void blankKeyIsRejectedByTheDatabase() {
        assertThatThrownBy(() -> insert("   ", HASH_A, source.getId(), destination.getId(), 100))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_payments_key_not_blank");
    }

    @Test
    void unknownSourceAccountIsRejectedByTheDatabase() {
        assertThatThrownBy(() -> insert(uniqueKey(), HASH_A, UUID.randomUUID(), destination.getId(), 100))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_payments_source");
    }

    private static String uniqueKey() {
        return "repo-" + UUID.randomUUID();
    }

    private void insert(String key, String hash, UUID sourceId, UUID destinationId, long amount) {
        jdbc.update("INSERT INTO payments (idempotency_key, request_hash, source_account_id, "
                        + "destination_account_id, amount) VALUES (?, ?, ?, ?, ?)",
                key, hash, sourceId, destinationId, amount);
    }
}
