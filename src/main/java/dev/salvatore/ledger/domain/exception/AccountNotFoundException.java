package dev.salvatore.ledger.domain.exception;

import java.util.UUID;

/** Thrown when an operation refers to an account that does not exist. */
public class AccountNotFoundException extends RuntimeException {

    private final UUID accountId;

    public AccountNotFoundException(UUID accountId) {
        super("Account %s not found".formatted(accountId));
        this.accountId = accountId;
    }

    public UUID getAccountId() {
        return accountId;
    }
}