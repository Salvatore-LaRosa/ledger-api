package dev.salvatore.ledger.domain.order;

/**
 * Lifecycle of an order. The transition rules live here, in the domain, so they can be
 * unit-tested without a database.
 *
 * <p>Allowed transitions: CREATED to PAID or CANCELLED; PAID to CONFIRMED or CANCELLED;
 * CONFIRMED to CONSOLIDATED or CANCELLED. CONSOLIDATED and CANCELLED are final.
 */
public enum OrderStatus {
    CREATED,
    PAID,
    CONFIRMED,
    CONSOLIDATED,
    CANCELLED;

    /**
     * Whether an order in this status may move to {@code target}. A transition to the
     * same status is not allowed: idempotent retries are handled by the service layer.
     */
    public boolean canTransitionTo(OrderStatus target) {
        // Exhaustive switch: adding a status without updating the rules does not compile.
        return switch (this) {
            case CREATED -> target == PAID || target == CANCELLED;
            case PAID -> target == CONFIRMED || target == CANCELLED;
            case CONFIRMED -> target == CONSOLIDATED || target == CANCELLED;
            case CONSOLIDATED, CANCELLED -> false;
        };
    }
}