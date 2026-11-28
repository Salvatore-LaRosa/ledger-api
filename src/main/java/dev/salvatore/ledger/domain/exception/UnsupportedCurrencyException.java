package dev.salvatore.ledger.domain.exception;

import java.util.Currency;

/** Thrown when no transit account exists for a currency, so orders in it cannot be handled. */
public class UnsupportedCurrencyException extends RuntimeException {

    private final Currency currency;

    public UnsupportedCurrencyException(Currency currency) {
        super("Currency %s is not supported".formatted(currency));
        this.currency = currency;
    }

    public Currency getCurrency() {
        return currency;
    }
}