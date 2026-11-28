package dev.salvatore.ledger.repository;

import dev.salvatore.ledger.domain.payment.Payment;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * Deliberately extends {@link Repository} and not JpaRepository: payments are written only
 * through {@link #insertIfAbsent}, so no save/delete methods are exposed.
 */
public interface PaymentRepository extends Repository<Payment, UUID> {

    /**
     * Inserts the payment unless the idempotency key already exists, and returns the number of
     * rows inserted (1 or 0). The UNIQUE constraint arbitrates, not an if-exists check.
     * <p>
     * In PostgreSQL ON CONFLICT DO NOTHING does not abort the transaction, and if the conflicting
     * row belongs to a concurrent transaction still in progress it waits for it: when that one
     * commits this returns 0, when it rolls back this inserts.
     */
    @Modifying
    @Query(value = """
            INSERT INTO payments (idempotency_key, request_hash, source_account_id,
                                  destination_account_id, amount)
            VALUES (:key, :hash, :source, :destination, :amount)
            ON CONFLICT (idempotency_key) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("key") String key,
                       @Param("hash") String hash,
                       @Param("source") UUID source,
                       @Param("destination") UUID destination,
                       @Param("amount") long amount);

    Optional<Payment> findByIdempotencyKey(String idempotencyKey);
}