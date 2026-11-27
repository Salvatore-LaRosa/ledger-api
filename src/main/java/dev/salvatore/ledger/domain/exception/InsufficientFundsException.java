package dev.salvatore.ledger.domain.exception;

import java.util.UUID;

/** Thrown when a debit would take a non-external account below zero. */
public class InsufficientFundsException extends RuntimeException {

    private final UUID accountId;
    private final long balance;
    private final long requested;

    public InsufficientFundsException(UUID accountId, long balance, long requested) {
        super("Account %s has balance %d and cannot be debited by %d"
                .formatted(accountId, balance, requested));
        this.accountId = accountId;
        this.balance = balance;
        this.requested = requested;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public long getBalance() {
        return balance;
    }

    public long getRequested() {
        return requested;
    }
}