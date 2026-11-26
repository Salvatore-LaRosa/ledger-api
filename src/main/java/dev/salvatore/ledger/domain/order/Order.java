package dev.salvatore.ledger.domain.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;

/**
 * A purchase between a buyer and a merchant account. Money movements are not made here:
 * the order only owns its lifecycle, the ledger entries are created by the service layer
 * in the same transaction as the status change.
 *
 * <p>Accounts are referenced by id, not with {@code @ManyToOne}: the order does not need
 * to load them, and it keeps the two aggregates independent.
 */
@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "buyer_account_id", nullable = false, updatable = false)
    private UUID buyerAccountId;

    @Column(name = "merchant_account_id", nullable = false, updatable = false)
    private UUID merchantAccountId;

    /** Minor units (e.g. cents). */
    @Column(nullable = false, updatable = false)
    private long amount;

    @Column(nullable = false, updatable = false, length = 3)
    private Currency currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    /** Optimistic lock. A wrapper type on purpose: null means "not persisted yet". */
    @Version
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** For JPA only. */
    protected Order() {
    }

    private Order(UUID buyerAccountId, UUID merchantAccountId, long amount, Currency currency) {
        this.buyerAccountId = buyerAccountId;
        this.merchantAccountId = merchantAccountId;
        this.amount = amount;
        this.currency = currency;
        this.status = OrderStatus.CREATED;
        this.createdAt = Instant.now();
    }

    public static Order create(UUID buyerAccountId, UUID merchantAccountId, long amount, Currency currency) {
        Objects.requireNonNull(buyerAccountId, "buyerAccountId must not be null");
        Objects.requireNonNull(merchantAccountId, "merchantAccountId must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive, got " + amount);
        }
        if (buyerAccountId.equals(merchantAccountId)) {
            throw new IllegalArgumentException("buyer and merchant must be different accounts");
        }
        return new Order(buyerAccountId, merchantAccountId, amount, currency);
    }

    public void markPaid() {
        transitionTo(OrderStatus.PAID);
    }

    public void markConfirmed() {
        transitionTo(OrderStatus.CONFIRMED);
    }

    public void markConsolidated() {
        transitionTo(OrderStatus.CONSOLIDATED);
    }

    public void cancel() {
        transitionTo(OrderStatus.CANCELLED);
    }

    private void transitionTo(OrderStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalOrderTransitionException(id, status, target);
        }
        this.status = target;
    }

    public UUID getId() {
        return id;
    }

    public UUID getBuyerAccountId() {
        return buyerAccountId;
    }

    public UUID getMerchantAccountId() {
        return merchantAccountId;
    }

    public long getAmount() {
        return amount;
    }

    public Currency getCurrency() {
        return currency;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public Long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}