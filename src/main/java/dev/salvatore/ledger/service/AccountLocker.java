package dev.salvatore.ledger.service;

import dev.salvatore.ledger.domain.account.Account;
import dev.salvatore.ledger.domain.exception.AccountNotFoundException;
import dev.salvatore.ledger.repository.AccountRepository;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The single place where accounts are locked. Locking always happens in ascending id order
 * (UUID natural ordering), so any mix of concurrent operations (transfers, reversals)
 * queues up in the same order and cannot deadlock.
 * <p>
 * The order is computed in Java on purpose: sorting with SQL's ORDER BY would follow
 * PostgreSQL's uuid ordering, which differs from {@link UUID#compareTo}.
 */
@Component
public class AccountLocker {

    private final AccountRepository accountRepository;

    public AccountLocker(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    /**
     * Locks every given account (each id once) and returns them by id. Must be called
     * inside a transaction, before any balance is read.
     *
     * @throws AccountNotFoundException if an account does not exist
     */
    public Map<UUID, Account> lockAll(Collection<UUID> accountIds) {
        Map<UUID, Account> locked = new LinkedHashMap<>();
        for (UUID id : new TreeSet<>(accountIds)) {
            Account account = accountRepository.findByIdForUpdate(id)
                    .orElseThrow(() -> new AccountNotFoundException(id));
            locked.put(id, account);
        }
        return locked;
    }
}