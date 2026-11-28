package dev.salvatore.ledger.service;

import dev.salvatore.ledger.domain.payment.Payment;

/**
 * The outcome of a payment request. {@code replayed} is true when the same idempotency key had
 * already been processed: the original payment is returned and no money moved again.
 */
public record PaymentResult(Payment payment, boolean replayed) {
}