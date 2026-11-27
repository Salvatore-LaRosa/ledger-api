package dev.salvatore.ledger.service;

import dev.salvatore.ledger.domain.account.Account;
import dev.salvatore.ledger.domain.exception.AccountNotFoundException;
import dev.salvatore.ledger.domain.exception.CurrencyMismatchException;
import dev.salvatore.ledger.domain.ledger.LedgerTransaction;
import dev.salvatore.ledger.domain.ledger.LedgerTransaction.EntryDraft;
import dev.salvatore.ledger.repository.AccountRepository;
import dev.salvatore.ledger.repository.LedgerTransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Moves money between two accounts. This is the only place that locks accounts and changes
 * balances, so the lock ordering below holds for the whole application.
 */
@Service
public class TransferService {

    private final AccountRepository accountRepository;
    private final LedgerTransactionRepository ledgerTransactionRepository;

    public TransferService(AccountRepository accountRepository,
                           LedgerTransactionRepository ledgerTransactionRepository) {
        this.accountRepository = accountRepository;
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

        // Always lock in ascending id order, whatever the direction of the transfer: two
        // opposite transfers (A to B and B to A) then queue on the same first lock instead
        // of each holding one account and waiting for the other (deadlock).
        boolean sourceFirst = sourceAccountId.compareTo(destinationAccountId) < 0;
        Account first = lock(sourceFirst ? sourceAccountId : destinationAccountId);
        Account second = lock(sourceFirst ? destinationAccountId : sourceAccountId);
        Account source = sourceFirst ? first : second;
        Account destination = sourceFirst ? second : first;

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

    private Account lock(UUID accountId) {
        return accountRepository.findByIdForUpdate(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
    }
}