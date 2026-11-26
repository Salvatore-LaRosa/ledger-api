package dev.salvatore.ledger.domain.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class OrderTest {

    private static final UUID BUYER = UUID.randomUUID();
    private static final UUID MERCHANT = UUID.randomUUID();
    private static final Currency EUR = Currency.getInstance("EUR");

    @Test
    void createStartsInCreatedStatus() {
        Order order = newOrder();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CREATED);
        assertThat(order.getBuyerAccountId()).isEqualTo(BUYER);
        assertThat(order.getMerchantAccountId()).isEqualTo(MERCHANT);
        assertThat(order.getAmount()).isEqualTo(1_000L);
        assertThat(order.getCurrency()).isEqualTo(EUR);
        assertThat(order.getCreatedAt()).isNotNull();
        assertThat(order.getId()).isNull();
        assertThat(order.getVersion()).isNull();
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void createRejectsNonPositiveAmount(long amount) {
        assertThatThrownBy(() -> Order.create(BUYER, MERCHANT, amount, EUR))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createRejectsSameBuyerAndMerchant() {
        assertThatThrownBy(() -> Order.create(BUYER, BUYER, 1_000L, EUR))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createRejectsNullArguments() {
        assertThatThrownBy(() -> Order.create(null, MERCHANT, 1_000L, EUR))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Order.create(BUYER, null, 1_000L, EUR))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Order.create(BUYER, MERCHANT, 1_000L, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void happyPathReachesConsolidated() {
        Order order = newOrder();

        order.markPaid();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        order.markConfirmed();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        order.markConsolidated();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONSOLIDATED);
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"CREATED", "PAID", "CONFIRMED"})
    void cancelIsAllowedFromEveryNonFinalStatus(OrderStatus status) {
        Order order = orderIn(status);

        order.cancel();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"CONSOLIDATED", "CANCELLED"})
    void finalStatesRejectEveryTransition(OrderStatus finalStatus) {
        Order order = orderIn(finalStatus);

        assertThatThrownBy(order::markPaid).isInstanceOf(IllegalOrderTransitionException.class);
        assertThatThrownBy(order::markConfirmed).isInstanceOf(IllegalOrderTransitionException.class);
        assertThatThrownBy(order::markConsolidated).isInstanceOf(IllegalOrderTransitionException.class);
        assertThatThrownBy(order::cancel).isInstanceOf(IllegalOrderTransitionException.class);
        assertThat(order.getStatus()).isEqualTo(finalStatus);
    }

    @Test
    void skippingAStepIsRejectedAndLeavesTheStatusUnchanged() {
        Order order = newOrder();

        assertThatThrownBy(order::markConfirmed).isInstanceOf(IllegalOrderTransitionException.class);
        assertThatThrownBy(order::markConsolidated).isInstanceOf(IllegalOrderTransitionException.class);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CREATED);
    }

    @Test
    void transitionToTheCurrentStatusIsRejected() {
        Order order = newOrder();
        order.markPaid();

        assertThatThrownBy(order::markPaid).isInstanceOf(IllegalOrderTransitionException.class);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    void exceptionDescribesTheRejectedTransition() {
        Order order = newOrder();

        assertThatThrownBy(order::markConfirmed)
                .isInstanceOfSatisfying(IllegalOrderTransitionException.class, ex -> {
                    assertThat(ex.getFrom()).isEqualTo(OrderStatus.CREATED);
                    assertThat(ex.getTo()).isEqualTo(OrderStatus.CONFIRMED);
                })
                .hasMessageContaining("CREATED")
                .hasMessageContaining("CONFIRMED");
    }

    private static Order newOrder() {
        return Order.create(BUYER, MERCHANT, 1_000L, EUR);
    }

    private static Order orderIn(OrderStatus target) {
        Order order = newOrder();
        switch (target) {
            case CREATED -> { }
            case PAID -> order.markPaid();
            case CONFIRMED -> {
                order.markPaid();
                order.markConfirmed();
            }
            case CONSOLIDATED -> {
                order.markPaid();
                order.markConfirmed();
                order.markConsolidated();
            }
            case CANCELLED -> order.cancel();
        }
        return order;
    }
}