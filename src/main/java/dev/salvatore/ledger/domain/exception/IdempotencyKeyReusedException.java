package dev.salvatore.ledger.domain.exception;

/** Thrown when an idempotency key is reused with a different request (a client error, HTTP 422). */
public class IdempotencyKeyReusedException extends RuntimeException {

    private final String idempotencyKey;

    public IdempotencyKeyReusedException(String idempotencyKey) {
        super("Idempotency key '%s' was already used with a different request".formatted(idempotencyKey));
        this.idempotencyKey = idempotencyKey;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }
}