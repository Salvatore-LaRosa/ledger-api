package dev.salvatore.ledger.repository;

import dev.salvatore.ledger.domain.account.Account;
import dev.salvatore.ledger.domain.account.AccountType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Currency;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    /**
     * Loads an account taking a pessimistic row lock (SELECT ... FOR UPDATE) that is held
     * until the surrounding transaction ends. It must be called inside a transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Id of the account of the given type and currency (for example the transit account of a
     * currency). A scalar projection on purpose: it does not load the entity, so no balance is
     * read before the account is locked.
     */
    @Query("select a.id from Account a where a.type = :type and a.currency = :currency")
    Optional<UUID> findIdByTypeAndCurrency(@Param("type") AccountType type,
                                           @Param("currency") Currency currency);
}