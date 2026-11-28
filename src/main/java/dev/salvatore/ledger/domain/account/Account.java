package dev.salvatore.ledger.domain.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;

import dev.salvatore.ledger.domain.exception.InsufficientFundsException;

/**
 * A ledger account. The balance is expressed in minor units (e.g. cents) and is a
 * denormalized value: ledger entries remain the source of truth.
 * <p>
 * There are deliberately no setters. The balance changes only through {@link #debit} and
 * {@link #credit}, which must be called inside a transaction that also writes the matching
 * ledger entries.
 */
@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountType type;

    @Column(nullable = false, length = 3)
    private Currency currency;

    @Column(nullable = false)
    private long balance;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Required by JPA. */
    protected Account() {
    }

    private Account(String name, AccountType type, Currency currency) {
        this.name = name;
        this.type = type;
        this.currency = currency;
        this.balance = 0L;
        this.createdAt = Instant.now();
    }

    /**
     * Opens a new account with a zero balance.
     *
     * @throws IllegalArgumentException if the name is blank
     * @throws NullPointerException     if name, type or currency is null
     */
    public static Account open(String name, AccountType type, Currency currency) {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        String stripped = name.strip();
        if (stripped.isEmpty()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        return new Account(stripped, type, currency);
    }

    /**
     * Money enters the account (the balance of a CREDIT ledger entry). Public because the
     * transfer service lives in another package: only call it inside a transaction that
     * also writes the matching ledger entry.
     *
     * @throws IllegalArgumentException if the amount is not positive
     * @throws ArithmeticException      if the balance would overflow a long
     */
    public void credit(long amount) {
        requirePositive(amount);
        this.balance = Math.addExact(balance, amount);
    }

    /**
     * Money leaves the account (a DEBIT ledger entry). Every account type except EXTERNAL
     * (the outside world, used as funding source) must stay at or above zero.
     *
     * @throws IllegalArgumentException   if the amount is not positive
     * @throws InsufficientFundsException if the balance is not enough
     * @throws ArithmeticException        if the balance would overflow a long
     */
    public void debit(long amount) {
        requirePositive(amount);
        long newBalance = Math.subtractExact(balance, amount);
        if (newBalance < 0 && type != AccountType.EXTERNAL) {
            throw new InsufficientFundsException(id, balance, amount);
        }
        this.balance = newBalance;
    }

    private static void requirePositive(long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive, got " + amount);
        }
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public AccountType getType() {
        return type;
    }

    public Currency getCurrency() {
        return currency;
    }

    public long getBalance() {
        return balance;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}