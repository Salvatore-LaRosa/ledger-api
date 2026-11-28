package dev.salvatore.ledger.domain.exception;

import java.util.UUID;

/** Thrown when a ledger transaction cannot be reversed (already reversed, or itself a reversal). */
public class ReversalNotAllowedException extends RuntimeException {

    private final UUID transactionId;

    public ReversalNotAllowedException(UUID transactionId, String reason) {
        super("Ledger transaction %s cannot be reversed: %s".formatted(transactionId, reason));
        this.transactionId = transactionId;
    }

    public UUID getTransactionId() {
        return transactionId;
    }
}