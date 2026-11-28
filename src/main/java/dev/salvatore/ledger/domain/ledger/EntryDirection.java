package dev.salvatore.ledger.domain.ledger;

/**
 * Direction of a ledger entry from the point of view of the account it touches.
 * <p>
 * Convention used in this project: DEBIT means money leaves the account (its balance
 * decreases), CREDIT means money enters it. This is a cash-movement convention, not
 * the textbook asset/liability one.
 */
public enum EntryDirection {
    DEBIT,
    CREDIT;

    /** The direction that cancels this one. */
    public EntryDirection opposite() {
        return this == DEBIT ? CREDIT : DEBIT;
    }
}