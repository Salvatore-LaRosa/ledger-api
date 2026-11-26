package dev.salvatore.ledger.domain.order;

import static dev.salvatore.ledger.domain.order.OrderStatus.CANCELLED;
import static dev.salvatore.ledger.domain.order.OrderStatus.CONFIRMED;
import static dev.salvatore.ledger.domain.order.OrderStatus.CONSOLIDATED;
import static dev.salvatore.ledger.domain.order.OrderStatus.CREATED;
import static dev.salvatore.ledger.domain.order.OrderStatus.PAID;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class OrderStatusTest {

    // Written out by hand, independently from the implementation: this is the spec.
    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED = Map.of(
            CREATED, EnumSet.of(PAID, CANCELLED),
            PAID, EnumSet.of(CONFIRMED, CANCELLED),
            CONFIRMED, EnumSet.of(CONSOLIDATED, CANCELLED),
            CONSOLIDATED, EnumSet.noneOf(OrderStatus.class),
            CANCELLED, EnumSet.noneOf(OrderStatus.class));

    @Test
    void specCoversEveryStatus() {
        // Fails when a status is added without deciding its transitions here too.
        assertThat(ALLOWED.keySet()).containsExactlyInAnyOrder(OrderStatus.values());
    }

    @ParameterizedTest(name = "{0} -> {1} allowed: {2}")
    @MethodSource("allPairs")
    void transitionMatrix(OrderStatus from, OrderStatus to, boolean expected) {
        assertThat(from.canTransitionTo(to)).isEqualTo(expected);
    }

    static Stream<Arguments> allPairs() {
        return Arrays.stream(OrderStatus.values()).flatMap(from ->
                Arrays.stream(OrderStatus.values()).map(to ->
                        Arguments.of(from, to, ALLOWED.get(from).contains(to))));
    }
}