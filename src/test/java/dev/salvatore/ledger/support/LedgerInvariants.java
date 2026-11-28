package dev.salvatore.ledger.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The invariants that must hold on the ledger no matter what ran concurrently. They are read
 * straight from the database with SQL, not through JPA, so they check what was really written.
 */
public final class LedgerInvariants {

    private static final String SIGNED_AMOUNT =
            "CASE e.direction WHEN 'CREDIT' THEN e.amount ELSE -e.amount END";

    private LedgerInvariants() {
    }

    /**
     * Asserts the ledger invariants over a <b>closed</b> group of accounts: every transaction
     * that touches one of them only touches accounts of the group (include the EXTERNAL account
     * used for funding).
     * <ol>
     *   <li>every transaction sums to zero (credits equal debits);</li>
     *   <li>every account balance equals the sum of its entries;</li>
     *   <li>no account other than EXTERNAL has a negative balance;</li>
     *   <li>the balances of the whole group sum to zero.</li>
     * </ol>
     */
    public static void assertHold(JdbcTemplate jdbc, Collection<UUID> accountIds) {
        String in = String.join(",", Collections.nCopies(accountIds.size(), "?"));
        Object[] args = accountIds.toArray();

        List<UUID> unbalanced = jdbc.query(
                "SELECT e.transaction_id FROM ledger_entries e WHERE e.account_id IN (" + in + ") "
                        + "GROUP BY e.transaction_id HAVING SUM(" + SIGNED_AMOUNT + ") <> 0",
                (rs, row) -> rs.getObject(1, UUID.class), args);
        assertThat(unbalanced).as("transactions whose entries do not sum to zero").isEmpty();

        List<UUID> mismatched = jdbc.query(
                "SELECT a.id FROM accounts a LEFT JOIN ledger_entries e ON e.account_id = a.id "
                        + "WHERE a.id IN (" + in + ") GROUP BY a.id, a.balance "
                        + "HAVING a.balance <> COALESCE(SUM(" + SIGNED_AMOUNT + "), 0)",
                (rs, row) -> rs.getObject(1, UUID.class), args);
        assertThat(mismatched).as("accounts whose balance differs from the sum of their entries").isEmpty();

        List<UUID> negative = jdbc.query(
                "SELECT id FROM accounts WHERE id IN (" + in + ") AND balance < 0 AND type <> 'EXTERNAL'",
                (rs, row) -> rs.getObject(1, UUID.class), args);
        assertThat(negative).as("non-external accounts with a negative balance").isEmpty();

        Long total = jdbc.queryForObject(
                "SELECT COALESCE(SUM(balance), 0) FROM accounts WHERE id IN (" + in + ")", Long.class, args);
        assertThat(total).as("sum of all balances in the group").isZero();
    }

    /**
     * System-wide: the balances of all the accounts in a currency sum to zero (the sum of all
     * ledger movements per currency is zero, since every transaction is balanced).
     */
    public static void assertCurrencyIsBalanced(JdbcTemplate jdbc, String currency) {
        Long total = jdbc.queryForObject(
                "SELECT COALESCE(SUM(balance), 0) FROM accounts WHERE currency = ?", Long.class, currency);
        assertThat(total).as("sum of all balances in " + currency).isZero();
    }

    /**
     * System-wide: the transit account of a currency holds exactly the amount of the orders
     * that are PAID or CONFIRMED in that currency.
     */
    public static void assertTransitMatchesOpenOrders(JdbcTemplate jdbc, String currency) {
        Long transit = jdbc.queryForObject(
                "SELECT COALESCE(SUM(balance), 0) FROM accounts WHERE type = 'TRANSIT' AND currency = ?",
                Long.class, currency);
        Long open = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM orders WHERE currency = ? AND status IN ('PAID', 'CONFIRMED')",
                Long.class, currency);
        assertThat(transit).as("transit balance must equal the orders in PAID or CONFIRMED").isEqualTo(open);
    }
}