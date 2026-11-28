package dev.salvatore.ledger.service;

import dev.salvatore.ledger.domain.account.Account;
import dev.salvatore.ledger.domain.exception.ReversalNotAllowedException;
import dev.salvatore.ledger.domain.exception.TransactionNotFoundException;
import dev.salvatore.ledger.domain.ledger.LedgerEntry;
import dev.salvatore.ledger.domain.ledger.LedgerTransaction;
import dev.salvatore.ledger.repository.LedgerTransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Cancels a ledger transaction by appending a new one that mirrors it. The original is never
 * touched. A transaction can be reversed at most once: the reversal has a deterministic
 * reference, and the UNIQUE constraint on {@code reference} is the real guarantee.
 */
@Service
public class ReversalService {

    private final LedgerTransactionRepository ledgerTransactionRepository;
    private final AccountLocker accountLocker;

    public ReversalService(LedgerTransactionRepository ledgerTransactionRepository,
                           AccountLocker accountLocker) {
        this.ledgerTransactionRepository = ledgerTransactionRepository;
        this.accountLocker = accountLocker;
    }

    /**
     * Reverses the whole transaction: every entry is mirrored and the balances move back.
     *
     * @throws TransactionNotFoundException if the transaction does not exist
     * @throws ReversalNotAllowedException  if it was already reversed or is itself a reversal
     * @throws dev.salvatore.ledger.domain.exception.InsufficientFundsException
     *                                      if an account no longer has the money to give back
     */
    @Transactional
    public LedgerTransaction reverse(UUID transactionId) {
        Objects.requireNonNull(transactionId, "transactionId must not be null");

        // Ledger rows are immutable, so reading the original before locking is safe.
        LedgerTransaction original = ledgerTransactionRepository.findById(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));

        // Reading an entry's account id does not load the account (the association is lazy),
        // so no balance is read before the lock: a stale cached balance would be overwritten.
        List<UUID> accountIds = original.getEntries().stream()
                .map(entry -> entry.getAccount().getId())
                .toList();
        accountLocker.lockAll(accountIds);

        // Checked after the lock: concurrent attempts to reverse the same transaction queue on
        // the same accounts, so the loser sees the winner's committed reversal here.
        String reference = referenceFor(transactionId);
        if (ledgerTransactionRepository.existsByReference(reference)) {
            throw new ReversalNotAllowedException(transactionId, "it has already been reversed");
        }

        LedgerTransaction reversal = original.reverse(reference);
        for (LedgerEntry entry : reversal.getEntries()) {
            Account account = entry.getAccount();
            switch (entry.getDirection()) {
                case DEBIT -> account.debit(entry.getAmount());
                case CREDIT -> account.credit(entry.getAmount());
            }
        }
        return ledgerTransactionRepository.saveAndFlush(reversal);
    }

    static String referenceFor(UUID transactionId) {
        return "reversal:" + transactionId;
    }
}