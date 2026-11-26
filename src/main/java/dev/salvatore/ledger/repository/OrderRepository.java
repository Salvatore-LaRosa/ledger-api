package dev.salvatore.ledger.repository;

import dev.salvatore.ledger.domain.order.Order;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, UUID> {
}