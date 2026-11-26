package dev.salvatore.ledger.domain.order;

import java.util.UUID;

/** Thrown when an order is asked to move to a status its current status does not allow. */
public class IllegalOrderTransitionException extends RuntimeException {

    private final UUID orderId;
    private final OrderStatus from;
    private final OrderStatus to;

    public IllegalOrderTransitionException(UUID orderId, OrderStatus from, OrderStatus to) {
        super("Order %s cannot move from %s to %s".formatted(orderId, from, to));
        this.orderId = orderId;
        this.from = from;
        this.to = to;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public OrderStatus getFrom() {
        return from;
    }

    public OrderStatus getTo() {
        return to;
    }
}