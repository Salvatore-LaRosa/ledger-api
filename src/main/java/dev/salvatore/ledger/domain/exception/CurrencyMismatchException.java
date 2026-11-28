package dev.salvatore.ledger.domain.exception;

import java.util.Currency;

/** Thrown when a transfer is attempted between accounts with different currencies. */
public class CurrencyMismatchException extends RuntimeException {

    private final Currency source;
    private final Currency destination;

    public CurrencyMismatchException(Currency source, Currency destination) {
        super("Cannot transfer between different currencies: %s and %s".formatted(source, destination));
        this.source = source;
        this.destination = destination;
    }

    public Currency getSource() {
        return source;
    }

    public Currency getDestination() {
        return destination;
    }
}