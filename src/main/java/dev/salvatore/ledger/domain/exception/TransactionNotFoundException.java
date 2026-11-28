package dev.salvatore.ledger.domain.exception;

import java.util.UUID;

/** Thrown when an operation refers to a ledger transaction that does not exist. */
public class TransactionNotFoundException extends RuntimeException {

    private final UUID transactionId;

    public TransactionNotFoundException(UUID transactionId) {
        super("Ledger transaction %s not found".formatted(transactionId));
        this.transactionId = transactionId;
    }

    public UUID getTransactionId() {
        return transactionId;
    }
}