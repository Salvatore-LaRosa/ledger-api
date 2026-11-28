package dev.salvatore.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PaymentCommandTest {

    private static final UUID SOURCE = UUID.randomUUID();
    private static final UUID DESTINATION = UUID.randomUUID();

    @Test
    void hashIsStableAndHexEncoded() {
        PaymentCommand first = new PaymentCommand(SOURCE, DESTINATION, 2_500);
        PaymentCommand second = new PaymentCommand(SOURCE, DESTINATION, 2_500);

        assertThat(first.requestHash()).isEqualTo(second.requestHash()).matches("[0-9a-f]{64}");
    }

    @Test
    void anyChangeToTheRequestChangesTheHash() {
        String base = new PaymentCommand(SOURCE, DESTINATION, 2_500).requestHash();

        assertThat(new PaymentCommand(SOURCE, DESTINATION, 2_501).requestHash()).isNotEqualTo(base);
        assertThat(new PaymentCommand(UUID.randomUUID(), DESTINATION, 2_500).requestHash()).isNotEqualTo(base);
        assertThat(new PaymentCommand(SOURCE, UUID.randomUUID(), 2_500).requestHash()).isNotEqualTo(base);
    }

    @Test
    void nullAccountsAreRejected() {
        assertThatThrownBy(() -> new PaymentCommand(null, DESTINATION, 100))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PaymentCommand(SOURCE, null, 100))
                .isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void nonPositiveAmountIsRejected(long amount) {
        assertThatThrownBy(() -> new PaymentCommand(SOURCE, DESTINATION, amount))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sameSourceAndDestinationIsRejected() {
        assertThatThrownBy(() -> new PaymentCommand(SOURCE, SOURCE, 100))
                .isInstanceOf(IllegalArgumentException.class);
    }
}