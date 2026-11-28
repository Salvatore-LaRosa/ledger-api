package dev.salvatore.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.salvatore.ledger.domain.account.Account;
import dev.salvatore.ledger.domain.account.AccountType;
import dev.salvatore.ledger.domain.exception.AccountNotFoundException;
import dev.salvatore.ledger.domain.exception.CurrencyMismatchException;
import dev.salvatore.ledger.domain.exception.OrderNotFoundException;
import dev.salvatore.ledger.domain.exception.UnsupportedCurrencyException;
import dev.salvatore.ledger.domain.order.IllegalOrderTransitionException;
import dev.salvatore.ledger.domain.order.Order;
import dev.salvatore.ledger.domain.order.OrderStatus;
import dev.salvatore.ledger.repository.AccountRepository;
import dev.salvatore.ledger.repository.LedgerTransactionRepository;
import dev.salvatore.ledger.repository.OrderRepository;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    private static final Currency EUR = Currency.getInstance("EUR");
    private static final Currency USD = Currency.getInstance("USD");
    private static final UUID ORDER_ID = UUID.randomUUID();
    private static final UUID BUYER = UUID.randomUUID();
    private static final UUID MERCHANT = UUID.randomUUID();
    private static final UUID TRANSIT = UUID.randomUUID();
    private static final UUID PAYMENT_TRANSACTION = UUID.randomUUID();
    private static final long AMOUNT = 2_500;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private LedgerTransactionRepository ledgerTransactionRepository;

    @Mock
    private TransferService transferService;

    @Mock
    private ReversalService reversalService;

    private OrderService service;

    @BeforeEach
    void setUp() {
        service = new OrderService(orderRepository, accountRepository,
                ledgerTransactionRepository, transferService, reversalService);
    }

    // creation

    @Test
    void createStoresAnOrderInCreatedStatus() {
        when(accountRepository.findById(BUYER)).thenReturn(Optional.of(account(AccountType.CUSTOMER, EUR)));
        when(accountRepository.findById(MERCHANT)).thenReturn(Optional.of(account(AccountType.MERCHANT, EUR)));
        when(accountRepository.findIdByTypeAndCurrency(AccountType.TRANSIT, EUR)).thenReturn(Optional.of(TRANSIT));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Order order = service.create(BUYER, MERCHANT, AMOUNT, EUR);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CREATED);
        assertThat(order.getAmount()).isEqualTo(AMOUNT);
        assertThat(order.getCurrency()).isEqualTo(EUR);
    }

    @Test
    void createRejectsAnUnknownBuyer() {
        when(accountRepository.findById(BUYER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(BUYER, MERCHANT, AMOUNT, EUR))
                .isInstanceOf(AccountNotFoundException.class);

        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    void createRejectsAnAccountWithAnotherCurrency() {
        when(accountRepository.findById(BUYER)).thenReturn(Optional.of(account(AccountType.CUSTOMER, USD)));
        when(accountRepository.findById(MERCHANT)).thenReturn(Optional.of(account(AccountType.MERCHANT, EUR)));

        assertThatThrownBy(() -> service.create(BUYER, MERCHANT, AMOUNT, EUR))
                .isInstanceOf(CurrencyMismatchException.class);

        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    void createRejectsACurrencyWithoutATransitAccount() {
        when(accountRepository.findById(BUYER)).thenReturn(Optional.of(account(AccountType.CUSTOMER, EUR)));
        when(accountRepository.findById(MERCHANT)).thenReturn(Optional.of(account(AccountType.MERCHANT, EUR)));
        when(accountRepository.findIdByTypeAndCurrency(AccountType.TRANSIT, EUR)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(BUYER, MERCHANT, AMOUNT, EUR))
                .isInstanceOf(UnsupportedCurrencyException.class);

        verify(orderRepository, never()).save(any(Order.class));
    }

    // pay

    @Test
    void payFlushesTheOrderBeforeMovingTheMoneyIntoTransit() {
        Order order = orderIn(OrderStatus.CREATED);
        stubOrder(order);
        stubTransit();
        stubSaveAndFlush();

        Order result = service.pay(ORDER_ID);

        assertThat(result.getStatus()).isEqualTo(OrderStatus.PAID);
        InOrder inOrder = inOrder(orderRepository, transferService);
        inOrder.verify(orderRepository).saveAndFlush(order);
        inOrder.verify(transferService).transfer("order:" + ORDER_ID + ":paid", BUYER, TRANSIT, AMOUNT);
    }

    @Test
    void payingAnAlreadyPaidOrderIsANoOp() {
        Order order = orderIn(OrderStatus.PAID);
        stubOrder(order);

        Order result = service.pay(ORDER_ID);

        assertThat(result.getStatus()).isEqualTo(OrderStatus.PAID);
        verify(orderRepository, never()).saveAndFlush(any(Order.class));
        verifyNoInteractions(transferService, reversalService);
    }

    @Test
    void payingAConfirmedOrderIsRejectedWithoutMovingMoney() {
        stubOrder(orderIn(OrderStatus.CONFIRMED));
        stubTransit();

        assertThatThrownBy(() -> service.pay(ORDER_ID)).isInstanceOf(IllegalOrderTransitionException.class);

        verifyNoInteractions(transferService);
    }

    // confirm and consolidate

    @Test
    void confirmDoesNotMoveMoney() {
        stubOrder(orderIn(OrderStatus.PAID));
        stubSaveAndFlush();

        Order result = service.confirm(ORDER_ID);

        assertThat(result.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        verifyNoInteractions(transferService, reversalService);
    }

    @Test
    void consolidateMovesTheMoneyFromTransitToTheMerchant() {
        stubOrder(orderIn(OrderStatus.CONFIRMED));
        stubTransit();
        stubSaveAndFlush();

        Order result = service.consolidate(ORDER_ID);

        assertThat(result.getStatus()).isEqualTo(OrderStatus.CONSOLIDATED);
        verify(transferService).transfer("order:" + ORDER_ID + ":consolidated", TRANSIT, MERCHANT, AMOUNT);
    }

    // cancel

    @Test
    void cancellingACreatedOrderMovesNoMoney() {
        stubOrder(orderIn(OrderStatus.CREATED));
        stubSaveAndFlush();

        Order result = service.cancel(ORDER_ID);

        assertThat(result.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        verifyNoInteractions(transferService, reversalService);
    }

    @Test
    void cancellingAPaidOrderReversesThePayment() {
        stubOrder(orderIn(OrderStatus.PAID));
        stubSaveAndFlush();
        when(ledgerTransactionRepository.findIdByReference("order:" + ORDER_ID + ":paid"))
                .thenReturn(Optional.of(PAYMENT_TRANSACTION));

        Order result = service.cancel(ORDER_ID);

        assertThat(result.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        verify(reversalService).reverse(PAYMENT_TRANSACTION);
    }

    @Test
    void aConsolidatedOrderCannotBeCancelled() {
        stubOrder(orderIn(OrderStatus.CONSOLIDATED));

        assertThatThrownBy(() -> service.cancel(ORDER_ID)).isInstanceOf(IllegalOrderTransitionException.class);

        verifyNoInteractions(transferService, reversalService);
    }

    @Test
    void cancellingAnAlreadyCancelledOrderIsANoOp() {
        stubOrder(orderIn(OrderStatus.CANCELLED));

        Order result = service.cancel(ORDER_ID);

        assertThat(result.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        verify(orderRepository, never()).saveAndFlush(any(Order.class));
        verifyNoInteractions(transferService, reversalService);
    }

    @Test
    void anUnknownOrderIsRejected() {
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.pay(ORDER_ID)).isInstanceOf(OrderNotFoundException.class);
    }

    private void stubOrder(Order order) {
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
    }

    private void stubTransit() {
        when(accountRepository.findIdByTypeAndCurrency(AccountType.TRANSIT, EUR)).thenReturn(Optional.of(TRANSIT));
    }

    private void stubSaveAndFlush() {
        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static Account account(AccountType type, Currency currency) {
        return Account.open("test-" + type, type, currency);
    }

    /** The id is generated by JPA on persist: simulate it. */
    private static Order orderIn(OrderStatus target) {
        Order order = Order.create(BUYER, MERCHANT, AMOUNT, EUR);
        ReflectionTestUtils.setField(order, "id", ORDER_ID);
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