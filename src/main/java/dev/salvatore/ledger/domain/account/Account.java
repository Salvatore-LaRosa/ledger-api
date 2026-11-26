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

/**
 * A ledger account. The balance is expressed in minor units (e.g. cents) and is a
 * denormalized value: ledger entries remain the source of truth.
 * <p>
 * There are deliberately no setters. State changes will be exposed as domain
 * methods in later commits, always inside a transaction that also writes ledger entries.
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