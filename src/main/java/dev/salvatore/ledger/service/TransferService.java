package dev.salvatore.ledger.service;

import dev.salvatore.ledger.domain.account.Account;
import dev.salvatore.ledger.domain.exception.AccountNotFoundException;
import dev.salvatore.ledger.domain.exception.CurrencyMismatchException;
import dev.salvatore.ledger.domain.ledger.LedgerTransaction;
import dev.salvatore.ledger.domain.ledger.LedgerTransaction.EntryDraft;
import dev.salvatore.ledger.repository.LedgerTransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Moves money between two accounts. Accounts are always locked through {@link AccountLocker},
 * so the lock ordering holds for the whole application.
 */
@Service
public class TransferService {

    private final AccountLocker accountLocker;
    private final LedgerTransactionRepository ledgerTransactionRepository;

    public TransferService(AccountLocker accountLocker,
                           LedgerTransactionRepository ledgerTransactionRepository) {
        this.accountLocker = accountLocker;
        this.ledgerTransactionRepository = ledgerTransactionRepository;
    }

    /**
     * Debits the source and credits the destination by {@code amount} (minor units),
     * writing a balanced ledger transaction in the same database transaction.
     *
     * @throws IllegalArgumentException   if the amount is not positive, the accounts are the
     *                                    same or the reference is blank
     * @throws AccountNotFoundException   if an account does not exist
     * @throws CurrencyMismatchException  if the accounts use different currencies
     * @throws dev.salvatore.ledger.domain.exception.InsufficientFundsException
     *                                    if the source cannot cover the amount
     */
    @Transactional
    public LedgerTransaction transfer(String reference, UUID sourceAccountId,
                                      UUID destinationAccountId, long amount) {
        Objects.requireNonNull(reference, "reference must not be null");
        Objects.requireNonNull(sourceAccountId, "sourceAccountId must not be null");
        Objects.requireNonNull(destinationAccountId, "destinationAccountId must not be null");
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive, got " + amount);
        }
        if (sourceAccountId.equals(destinationAccountId)) {
            throw new IllegalArgumentException("source and destination accounts must be different");
        }

        Map<UUID, Account> locked = accountLocker.lockAll(List.of(sourceAccountId, destinationAccountId));
        Account source = locked.get(sourceAccountId);
        Account destination = locked.get(destinationAccountId);

        if (!source.getCurrency().equals(destination.getCurrency())) {
            throw new CurrencyMismatchException(source.getCurrency(), destination.getCurrency());
        }

        // Built before touching any balance: it validates the reference and the
        // double-entry invariants, and nothing is persisted until saveAndFlush below.
        LedgerTransaction transaction = LedgerTransaction.create(reference, List.of(
                EntryDraft.debit(source, amount),
                EntryDraft.credit(destination, amount)));

        source.debit(amount);
        destination.credit(amount);

        // Flush now so a duplicate reference (UNIQUE constraint) fails inside this transaction.
        return ledgerTransactionRepository.saveAndFlush(transaction);
    }
}