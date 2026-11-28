package dev.salvatore.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.salvatore.ledger.domain.exception.AccountNotFoundException;
import dev.salvatore.ledger.domain.exception.IdempotencyKeyReusedException;
import dev.salvatore.ledger.domain.payment.Payment;
import dev.salvatore.ledger.repository.AccountRepository;
import dev.salvatore.ledger.repository.PaymentRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    private static final UUID SOURCE = UUID.randomUUID();
    private static final UUID DESTINATION = UUID.randomUUID();
    private static final PaymentCommand COMMAND = new PaymentCommand(SOURCE, DESTINATION, 2_500);

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransferService transferService;

    @Mock
    private Payment payment;

    private PaymentService service;

    @BeforeEach
    void setUp() {
        service = new PaymentService(paymentRepository, accountRepository, transferService);
    }

    @Test
    void aNewKeyExecutesTheTransferAndReturnsTheCreatedPayment() {
        accountsExist();
        when(paymentRepository.insertIfAbsent("key-1", COMMAND.requestHash(), SOURCE, DESTINATION, 2_500))
                .thenReturn(1);
        when(paymentRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(payment));

        PaymentResult result = service.pay("key-1", COMMAND);

        assertThat(result.replayed()).isFalse();
        assertThat(result.payment()).isSameAs(payment);
        verify(transferService).transfer("payment:key-1", SOURCE, DESTINATION, 2_500);
    }

    @Test
    void aKnownKeyWithTheSameRequestReplaysWithoutMovingMoney() {
        accountsExist();
        when(paymentRepository.insertIfAbsent("key-1", COMMAND.requestHash(), SOURCE, DESTINATION, 2_500))
                .thenReturn(0);
        when(paymentRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(payment));
        when(payment.getRequestHash()).thenReturn(COMMAND.requestHash());

        PaymentResult result = service.pay("key-1", COMMAND);

        assertThat(result.replayed()).isTrue();
        assertThat(result.payment()).isSameAs(payment);
        verifyNoInteractions(transferService);
    }

    @Test
    void aKnownKeyWithADifferentRequestIsRejected() {
        accountsExist();
        when(paymentRepository.insertIfAbsent("key-1", COMMAND.requestHash(), SOURCE, DESTINATION, 2_500))
                .thenReturn(0);
        when(paymentRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(payment));
        when(payment.getRequestHash()).thenReturn("0".repeat(64));

        assertThatThrownBy(() -> service.pay("key-1", COMMAND))
                .isInstanceOf(IdempotencyKeyReusedException.class);

        verifyNoInteractions(transferService);
    }

    @Test
    void anUnknownSourceAccountIsRejectedBeforeAnythingIsWritten() {
        when(accountRepository.existsById(SOURCE)).thenReturn(false);

        assertThatThrownBy(() -> service.pay("key-1", COMMAND))
                .isInstanceOfSatisfying(AccountNotFoundException.class,
                        ex -> assertThat(ex.getAccountId()).isEqualTo(SOURCE));

        verifyNoInteractions(paymentRepository, transferService);
    }

    @Test
    void anUnknownDestinationAccountIsRejectedBeforeAnythingIsWritten() {
        when(accountRepository.existsById(SOURCE)).thenReturn(true);
        when(accountRepository.existsById(DESTINATION)).thenReturn(false);

        assertThatThrownBy(() -> service.pay("key-1", COMMAND))
                .isInstanceOfSatisfying(AccountNotFoundException.class,
                        ex -> assertThat(ex.getAccountId()).isEqualTo(DESTINATION));

        verifyNoInteractions(paymentRepository, transferService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void aBlankKeyIsRejected(String key) {
        assertThatThrownBy(() -> service.pay(key, COMMAND)).isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(accountRepository, paymentRepository, transferService);
    }

    @Test
    void aKeyLongerThanTheLimitIsRejected() {
        String tooLong = "k".repeat(PaymentService.MAX_KEY_LENGTH + 1);

        assertThatThrownBy(() -> service.pay(tooLong, COMMAND)).isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(accountRepository, paymentRepository, transferService);
    }

    private void accountsExist() {
        when(accountRepository.existsById(SOURCE)).thenReturn(true);
        when(accountRepository.existsById(DESTINATION)).thenReturn(true);
    }
}