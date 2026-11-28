package dev.salvatore.ledger.support;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.salvatore.ledger.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves the system-wide checkers can fail. The tampering happens inside a transaction that is
 * always rolled back, so it never reaches the committed data other tests rely on.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SystemInvariantsTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void bothCheckersPassOnAConsistentSystem() {
        assertThatCode(() -> {
            LedgerInvariants.assertCurrencyIsBalanced(jdbc, "EUR");
            LedgerInvariants.assertTransitMatchesOpenOrders(jdbc, "EUR");
        }).doesNotThrowAnyException();
    }

    @Test
    void aTamperedTransitBalanceIsDetected() {
        inRolledBackTransaction(() -> {
            tamperWithTheTransitBalance();

            assertThatThrownBy(() -> LedgerInvariants.assertTransitMatchesOpenOrders(jdbc, "EUR"))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("transit balance");
        });

        assertThatCode(() -> LedgerInvariants.assertTransitMatchesOpenOrders(jdbc, "EUR"))
                .doesNotThrowAnyException();
    }

    @Test
    void anUnbalancedCurrencyIsDetected() {
        inRolledBackTransaction(() -> {
            tamperWithTheTransitBalance();

            assertThatThrownBy(() -> LedgerInvariants.assertCurrencyIsBalanced(jdbc, "EUR"))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("sum of all balances");
        });

        assertThatCode(() -> LedgerInvariants.assertCurrencyIsBalanced(jdbc, "EUR"))
                .doesNotThrowAnyException();
    }

    private void tamperWithTheTransitBalance() {
        jdbc.update("UPDATE accounts SET balance = balance + 1 WHERE type = 'TRANSIT' AND currency = 'EUR'");
    }

    private void inRolledBackTransaction(Runnable action) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            action.run();
            status.setRollbackOnly();
        });
    }
}