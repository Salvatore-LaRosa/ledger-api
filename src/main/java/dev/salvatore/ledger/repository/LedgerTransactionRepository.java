package dev.salvatore.ledger.repository;

import dev.salvatore.ledger.domain.ledger.LedgerTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface LedgerTransactionRepository extends JpaRepository<LedgerTransaction, UUID> {

    Optional<LedgerTransaction> findByReference(String reference);

    boolean existsByReference(String reference);

    /** Id of the transaction with the given reference, without loading the entity. */
    @Query("select t.id from LedgerTransaction t where t.reference = :reference")
    Optional<UUID> findIdByReference(@Param("reference") String reference);
}