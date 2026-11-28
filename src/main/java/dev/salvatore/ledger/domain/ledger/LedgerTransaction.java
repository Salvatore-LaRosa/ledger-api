package dev.salvatore.ledger.domain.ledger;

import dev.salvatore.ledger.domain.account.Account;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import dev.salvatore.ledger.domain.exception.ReversalNotAllowedException;

/**
 * An atomic, balanced set of ledger entries (double-entry bookkeeping).
 * <p>
 * The invariants (at least two entries, positive amounts, single currency, total debits
 * equal to total credits) are checked here, so an unbalanced transaction cannot even be
 * built. The entity is immutable: once written it is never updated or deleted; mistakes are
 * fixed with a new, reversing transaction.
 */
@Entity
@Immutable
@Table(name = "ledger_transactions")
public class LedgerTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 100, updatable = false)
    private String reference;

    /** Id of the transaction this one reverses, or null. */
    @Column(name = "reversal_of_id", updatable = false)
    private UUID reversalOfId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "transaction", cascade = CascadeType.PERSIST)
    private List<LedgerEntry> entries = new ArrayList<>();

    /** Required by JPA. */
    protected LedgerTransaction() {
    }

    private LedgerTransaction(String reference) {
        this.reference = reference;
        this.createdAt = Instant.now();
    }

    /**
     * Builds a balanced transaction.
     *
     * @throws IllegalArgumentException if the reference is blank, there are fewer than two
     *                                  entries, an amount is not positive, accounts have
     *                                  different currencies or debits differ from credits
     * @throws ArithmeticException      if the totals overflow a long
     */
    public static LedgerTransaction create(String reference, List<EntryDraft> drafts) {
        Objects.requireNonNull(reference, "reference must not be null");
        Objects.requireNonNull(drafts, "drafts must not be null");
        String stripped = reference.strip();
        if (stripped.isEmpty()) {
            throw new IllegalArgumentException("reference must not be blank");
        }
        if (drafts.size() < 2) {
            throw new IllegalArgumentException("a transaction needs at least two entries");
        }

        Currency currency = drafts.getFirst().account().getCurrency();
        long totalDebits = 0L;
        long totalCredits = 0L;
        for (EntryDraft draft : drafts) {
            if (draft.amount() <= 0) {
                throw new IllegalArgumentException("entry amount must be positive");
            }
            if (!draft.account().getCurrency().equals(currency)) {
                throw new IllegalArgumentException("all entries must use the same currency");
            }
            switch (draft.direction()) {
                case DEBIT -> totalDebits = Math.addExact(totalDebits, draft.amount());
                case CREDIT -> totalCredits = Math.addExact(totalCredits, draft.amount());
            }
        }
        if (totalDebits != totalCredits) {
            throw new IllegalArgumentException(
                    "transaction is not balanced: debits=" + totalDebits + ", credits=" + totalCredits);
        }

        LedgerTransaction transaction = new LedgerTransaction(stripped);
        for (EntryDraft draft : drafts) {
            transaction.entries.add(
                    new LedgerEntry(transaction, draft.account(), draft.direction(), draft.amount()));
        }
        return transaction;
    }

    /**
     * Builds the transaction that cancels this one: same accounts and amounts, opposite
     * directions. History is never edited: a mistake is fixed by appending this reversal.
     *
     * @throws IllegalStateException       if this transaction has not been persisted yet
     * @throws ReversalNotAllowedException if this transaction is itself a reversal
     */
    public LedgerTransaction reverse(String reference) {
        if (id == null) {
            throw new IllegalStateException("only a persisted transaction can be reversed");
        }
        if (reversalOfId != null) {
            throw new ReversalNotAllowedException(id, "a reversal cannot be reversed");
        }
        List<EntryDraft> drafts = entries.stream()
                .map(entry -> new EntryDraft(
                        entry.getAccount(), entry.getDirection().opposite(), entry.getAmount()))
                .toList();
        LedgerTransaction reversal = create(reference, drafts);
        reversal.reversalOfId = id;
        return reversal;
    }

    public UUID getId() {
        return id;
    }

    public String getReference() {
        return reference;
    }

    public UUID getReversalOfId() {
        return reversalOfId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<LedgerEntry> getEntries() {
        return Collections.unmodifiableList(entries);
    }

    /**
     * Input for {@link #create}: what to do to which account, before the entity exists.
     */
    public record EntryDraft(Account account, EntryDirection direction, long amount) {

        public EntryDraft {
            Objects.requireNonNull(account, "account must not be null");
            Objects.requireNonNull(direction, "direction must not be null");
        }

        public static EntryDraft debit(Account account, long amount) {
            return new EntryDraft(account, EntryDirection.DEBIT, amount);
        }

        public static EntryDraft credit(Account account, long amount) {
            return new EntryDraft(account, EntryDirection.CREDIT, amount);
        }
    }
}