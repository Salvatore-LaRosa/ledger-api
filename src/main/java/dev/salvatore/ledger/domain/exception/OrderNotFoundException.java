package dev.salvatore.ledger.domain.exception;

import java.util.UUID;

/** Thrown when an operation refers to an order that does not exist. */
public class OrderNotFoundException extends RuntimeException {

    private final UUID orderId;

    public OrderNotFoundException(UUID orderId) {
        super("Order %s not found".formatted(orderId));
        this.orderId = orderId;
    }

    public UUID getOrderId() {
        return orderId;
    }
}