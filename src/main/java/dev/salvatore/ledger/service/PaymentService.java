package dev.salvatore.ledger.service;

import dev.salvatore.ledger.domain.exception.AccountNotFoundException;
import dev.salvatore.ledger.domain.exception.IdempotencyKeyReusedException;
import dev.salvatore.ledger.domain.payment.Payment;
import dev.salvatore.ledger.repository.AccountRepository;
import dev.salvatore.ledger.repository.PaymentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/**
 * Executes a payment at most once per idempotency key. The record of the payment and the money
 * movement share one database transaction: either both exist or neither does.
 */
@Service
public class PaymentService {

    static final int MAX_KEY_LENGTH = 64;
    private static final String LEDGER_REFERENCE_PREFIX = "payment:";

    private final PaymentRepository paymentRepository;
    private final AccountRepository accountRepository;
    private final TransferService transferService;

    public PaymentService(PaymentRepository paymentRepository,
                          AccountRepository accountRepository,
                          TransferService transferService) {
        this.paymentRepository = paymentRepository;
        this.accountRepository = accountRepository;
        this.transferService = transferService;
    }

    /**
     * Pays {@code command.amount()} from the source to the destination account.
     * <ul>
     *   <li>new key: the payment is executed and recorded;</li>
     *   <li>known key, same request: the original payment is returned, nothing moves;</li>
     *   <li>known key, different request: {@link IdempotencyKeyReusedException}.</li>
     * </ul>
     * A failed attempt (for example insufficient funds) rolls everything back, key included, so
     * the key is not consumed and the client may retry.
     *
     * @throws IllegalArgumentException        if the key is blank or longer than 64 characters
     * @throws AccountNotFoundException        if an account does not exist
     * @throws IdempotencyKeyReusedException   if the key was used with a different request
     * @throws dev.salvatore.ledger.domain.exception.InsufficientFundsException
     *                                         if the source cannot cover the amount
     */
    @Transactional
    public PaymentResult pay(String idempotencyKey, PaymentCommand command) {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        Objects.requireNonNull(command, "command must not be null");
        if (idempotencyKey.isBlank() || idempotencyKey.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException(
                    "idempotency key must be between 1 and " + MAX_KEY_LENGTH + " characters");
        }

        // Plain existence checks, no lock: they only turn a foreign key violation into a clear error.
        requireAccount(command.sourceAccountId());
        requireAccount(command.destinationAccountId());

        String requestHash = command.requestHash();
        int inserted = paymentRepository.insertIfAbsent(idempotencyKey, requestHash,
                command.sourceAccountId(), command.destinationAccountId(), command.amount());

        if (inserted == 0) {
            Payment existing = load(idempotencyKey);
            if (!existing.getRequestHash().equals(requestHash)) {
                throw new IdempotencyKeyReusedException(idempotencyKey);
            }
            return new PaymentResult(existing, true);
        }

        // Joins this transaction: if the transfer fails, the inserted row is rolled back too.
        transferService.transfer(LEDGER_REFERENCE_PREFIX + idempotencyKey,
                command.sourceAccountId(), command.destinationAccountId(), command.amount());
        return new PaymentResult(load(idempotencyKey), false);
    }

    private void requireAccount(UUID accountId) {
        if (!accountRepository.existsById(accountId)) {
            throw new AccountNotFoundException(accountId);
        }
    }

    private Payment load(String idempotencyKey) {
        return paymentRepository.findByIdempotencyKey(idempotencyKey)
                .orElseThrow(() -> new IllegalStateException(
                        "payment for idempotency key '%s' should exist".formatted(idempotencyKey)));
    }
}