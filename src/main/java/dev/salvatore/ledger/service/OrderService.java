package dev.salvatore.ledger.service;

import dev.salvatore.ledger.domain.account.Account;
import dev.salvatore.ledger.domain.account.AccountType;
import dev.salvatore.ledger.domain.exception.AccountNotFoundException;
import dev.salvatore.ledger.domain.exception.CurrencyMismatchException;
import dev.salvatore.ledger.domain.exception.OrderNotFoundException;
import dev.salvatore.ledger.domain.exception.UnsupportedCurrencyException;
import dev.salvatore.ledger.domain.order.Order;
import dev.salvatore.ledger.domain.order.OrderStatus;
import dev.salvatore.ledger.repository.AccountRepository;
import dev.salvatore.ledger.repository.LedgerTransactionRepository;
import dev.salvatore.ledger.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Currency;
import java.util.UUID;

/**
 * Drives the order lifecycle and the money movements tied to it.
 *
 * <p>Every transition follows the same order: change the status and flush the order first, then
 * move the money. The flush makes the optimistic lock ({@code @Version}) fire immediately: of two
 * concurrent requests on the same order the loser fails with an
 * {@code ObjectOptimisticLockingFailureException} before touching accounts or the ledger. If the
 * money movement fails afterwards, the whole transaction rolls back, order update included.
 *
 * <p>A request for the status the order is already in is a retry and returns the same outcome
 * without moving money again; any other illegal transition raises
 * {@link dev.salvatore.ledger.domain.order.IllegalOrderTransitionException}.
 */
@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final AccountRepository accountRepository;
    private final LedgerTransactionRepository ledgerTransactionRepository;
    private final TransferService transferService;
    private final ReversalService reversalService;

    public OrderService(OrderRepository orderRepository,
                        AccountRepository accountRepository,
                        LedgerTransactionRepository ledgerTransactionRepository,
                        TransferService transferService,
                        ReversalService reversalService) {
        this.orderRepository = orderRepository;
        this.accountRepository = accountRepository;
        this.ledgerTransactionRepository = ledgerTransactionRepository;
        this.transferService = transferService;
        this.reversalService = reversalService;
    }

    /**
     * Creates an order in CREATED status. The order currency must be the one of both accounts and
     * a transit account must exist for it.
     *
     * @throws AccountNotFoundException     if an account does not exist
     * @throws CurrencyMismatchException    if an account uses another currency
     * @throws UnsupportedCurrencyException if there is no transit account for the currency
     */
    @Transactional
    public Order create(UUID buyerAccountId, UUID merchantAccountId, long amount, Currency currency) {
        Order order = Order.create(buyerAccountId, merchantAccountId, amount, currency);

        Account buyer = account(buyerAccountId);
        Account merchant = account(merchantAccountId);
        requireCurrency(buyer, currency);
        requireCurrency(merchant, currency);
        transitAccountId(currency);

        return orderRepository.save(order);
    }

    /** CREATED to PAID: the amount moves from the buyer to the transit account (escrow). */
    @Transactional
    public Order pay(UUID orderId) {
        Order order = load(orderId);
        if (order.getStatus() == OrderStatus.PAID) {
            return order;
        }
        UUID transit = transitAccountId(order.getCurrency());
        order.markPaid();
        orderRepository.saveAndFlush(order);
        transferService.transfer(paidReference(order),
                order.getBuyerAccountId(), transit, order.getAmount());
        return order;
    }

    /** PAID to CONFIRMED: no money moves, the funds stay in the transit account. */
    @Transactional
    public Order confirm(UUID orderId) {
        Order order = load(orderId);
        if (order.getStatus() == OrderStatus.CONFIRMED) {
            return order;
        }
        order.markConfirmed();
        return orderRepository.saveAndFlush(order);
    }

    /** CONFIRMED to CONSOLIDATED: the amount moves from the transit account to the merchant. */
    @Transactional
    public Order consolidate(UUID orderId) {
        Order order = load(orderId);
        if (order.getStatus() == OrderStatus.CONSOLIDATED) {
            return order;
        }
        UUID transit = transitAccountId(order.getCurrency());
        order.markConsolidated();
        orderRepository.saveAndFlush(order);
        transferService.transfer("order:%s:consolidated".formatted(order.getId()),
                transit, order.getMerchantAccountId(), order.getAmount());
        return order;
    }

    /**
     * Cancels the order. If the payment was already made (PAID or CONFIRMED) it is reversed with
     * a new mirrored transaction; a CONSOLIDATED order cannot be cancelled (no refunds once the
     * merchant has been paid).
     */
    @Transactional
    public Order cancel(UUID orderId) {
        Order order = load(orderId);
        if (order.getStatus() == OrderStatus.CANCELLED) {
            return order;
        }
        boolean paymentMade = order.getStatus() == OrderStatus.PAID
                || order.getStatus() == OrderStatus.CONFIRMED;
        order.cancel();
        orderRepository.saveAndFlush(order);
        if (paymentMade) {
            UUID paymentTransactionId = ledgerTransactionRepository.findIdByReference(paidReference(order))
                    .orElseThrow(() -> new IllegalStateException(
                            "order %s is paid but has no payment transaction".formatted(order.getId())));
            reversalService.reverse(paymentTransactionId);
        }
        return order;
    }

    private Order load(UUID orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    private Account account(UUID accountId) {
        return accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
    }

    private static void requireCurrency(Account account, Currency currency) {
        if (!account.getCurrency().equals(currency)) {
            throw new CurrencyMismatchException(currency, account.getCurrency());
        }
    }

    /** Scalar lookup: no account entity is loaded before the transfer locks the accounts. */
    private UUID transitAccountId(Currency currency) {
        return accountRepository.findIdByTypeAndCurrency(AccountType.TRANSIT, currency)
                .orElseThrow(() -> new UnsupportedCurrencyException(currency));
    }

    private static String paidReference(Order order) {
        return "order:%s:paid".formatted(order.getId());
    }
}