package dev.salvatore.ledger.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.salvatore.ledger.TestcontainersConfiguration;
import dev.salvatore.ledger.domain.account.Account;
import dev.salvatore.ledger.domain.account.AccountType;
import dev.salvatore.ledger.domain.order.Order;
import dev.salvatore.ledger.domain.order.OrderStatus;
import jakarta.persistence.EntityManager;
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
class OrderRepositoryTest {

    private static final Currency XTS = Currency.getInstance("XTS");

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbc;

    private Account buyer;
    private Account merchant;

    @BeforeEach
    void createAccounts() {
        buyer = accountRepository.saveAndFlush(Account.open("buyer", AccountType.CUSTOMER, XTS));
        merchant = accountRepository.saveAndFlush(Account.open("merchant", AccountType.MERCHANT, XTS));
    }

    @Test
    void persistsOrderWithGeneratedIdAndInitialVersion() {
        Order saved = orderRepository.saveAndFlush(
                Order.create(buyer.getId(), merchant.getId(), 2_500L, XTS));
        entityManager.clear();

        Order loaded = orderRepository.findById(saved.getId()).orElseThrow();

        assertThat(loaded.getId()).isNotNull();
        assertThat(loaded.getBuyerAccountId()).isEqualTo(buyer.getId());
        assertThat(loaded.getMerchantAccountId()).isEqualTo(merchant.getId());
        assertThat(loaded.getAmount()).isEqualTo(2_500L);
        assertThat(loaded.getCurrency()).isEqualTo(XTS);
        assertThat(loaded.getStatus()).isEqualTo(OrderStatus.CREATED);
        assertThat(loaded.getVersion()).isZero();
        assertThat(loaded.getCreatedAt()).isNotNull();
    }

    @Test
    void statusChangeIsPersistedAndIncrementsTheVersion() {
        Order order = orderRepository.saveAndFlush(
                Order.create(buyer.getId(), merchant.getId(), 2_500L, XTS));

        order.markPaid();
        orderRepository.flush();

        assertThat(order.getVersion()).isEqualTo(1L);
        entityManager.clear();
        Order loaded = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(loaded.getVersion()).isEqualTo(1L);
    }

    // The constraint tests use direct INSERTs: they prove the database enforces the rules
    // even when the domain is bypassed.

    @Test
    void zeroAmountIsRejectedByTheDatabase() {
        assertThatThrownBy(() -> insertOrder(buyer.getId(), merchant.getId(), 0, "XTS", "CREATED"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_orders_amount_positive");
    }

    @Test
    void unknownStatusIsRejectedByTheDatabase() {
        assertThatThrownBy(() -> insertOrder(buyer.getId(), merchant.getId(), 100, "XTS", "REFUNDED"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_orders_status");
    }

    @Test
    void malformedCurrencyIsRejectedByTheDatabase() {
        assertThatThrownBy(() -> insertOrder(buyer.getId(), merchant.getId(), 100, "xts", "CREATED"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_orders_currency");
    }

    @Test
    void sameBuyerAndMerchantIsRejectedByTheDatabase() {
        assertThatThrownBy(() -> insertOrder(buyer.getId(), buyer.getId(), 100, "XTS", "CREATED"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_orders_distinct_accounts");
    }

    @Test
    void unknownBuyerAccountIsRejectedByTheDatabase() {
        assertThatThrownBy(() -> insertOrder(UUID.randomUUID(), merchant.getId(), 100, "XTS", "CREATED"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_orders_buyer");
    }

    private void insertOrder(UUID buyerId, UUID merchantId, long amount, String currency, String status) {
        jdbc.update("INSERT INTO orders (buyer_account_id, merchant_account_id, amount, currency, status) "
                + "VALUES (?, ?, ?, ?, ?)", buyerId, merchantId, amount, currency, status);
    }
}