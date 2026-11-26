package dev.salvatore.ledger.domain.ledger;

import dev.salvatore.ledger.domain.account.Account;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.util.UUID;

/**
 * A single movement on one account. Immutable: it can only be created through
 * {@link LedgerTransaction#create}, never modified afterwards.
 */
@Entity
@Immutable
@Table(name = "ledger_entries")
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transaction_id", nullable = false, updatable = false)
    private LedgerTransaction transaction;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false, updatable = false)
    private Account account;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 6, updatable = false)
    private EntryDirection direction;

    /** Always positive, in minor units. */
    @Column(nullable = false, updatable = false)
    private long amount;

    /** Required by JPA. */
    protected LedgerEntry() {
    }

    /** Package-private: entries are created only by {@link LedgerTransaction}. */
    LedgerEntry(LedgerTransaction transaction, Account account, EntryDirection direction, long amount) {
        this.transaction = transaction;
        this.account = account;
        this.direction = direction;
        this.amount = amount;
    }

    public UUID getId() {
        return id;
    }

    public LedgerTransaction getTransaction() {
        return transaction;
    }

    public Account getAccount() {
        return account;
    }

    public EntryDirection getDirection() {
        return direction;
    }

    public long getAmount() {
        return amount;
    }
}