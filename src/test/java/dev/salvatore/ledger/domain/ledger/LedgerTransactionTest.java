package dev.salvatore.ledger.domain.ledger;

import dev.salvatore.ledger.domain.account.Account;
import dev.salvatore.ledger.domain.account.AccountType;
import dev.salvatore.ledger.domain.ledger.LedgerTransaction.EntryDraft;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Currency;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

class LedgerTransactionTest {

    private static final Currency EUR = Currency.getInstance("EUR");
    private static final Currency USD = Currency.getInstance("USD");

    private final Account alice = Account.open("Alice", AccountType.CUSTOMER, EUR);
    private final Account bob = Account.open("Bob", AccountType.MERCHANT, EUR);

    @Test
    void createsBalancedTransactionWithItsEntries() {
        LedgerTransaction tx = LedgerTransaction.create("  ref-1  ", List.of(
                EntryDraft.debit(alice, 1_000),
                EntryDraft.credit(bob, 1_000)));

        assertThat(tx.getReference()).isEqualTo("ref-1");
        assertThat(tx.getCreatedAt()).isNotNull();
        assertThat(tx.getReversalOfId()).isNull();
        assertThat(tx.getEntries())
                .extracting(LedgerEntry::getAccount, LedgerEntry::getDirection, LedgerEntry::getAmount)
                .containsExactlyInAnyOrder(
                        tuple(alice, EntryDirection.DEBIT, 1_000L),
                        tuple(bob, EntryDirection.CREDIT, 1_000L));
        assertThat(tx.getEntries()).allSatisfy(entry -> assertThat(entry.getTransaction()).isSameAs(tx));
    }

    @Test
    void rejectsUnbalancedEntries() {
        assertThatThrownBy(() -> LedgerTransaction.create("ref-1", List.of(
                EntryDraft.debit(alice, 1_000),
                EntryDraft.credit(bob, 900))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not balanced");
    }

    @Test
    void rejectsFewerThanTwoEntries() {
        assertThatThrownBy(() -> LedgerTransaction.create("ref-1", List.of(EntryDraft.debit(alice, 1_000))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void rejectsNonPositiveAmount(long amount) {
        assertThatThrownBy(() -> LedgerTransaction.create("ref-1", List.of(
                EntryDraft.debit(alice, amount),
                EntryDraft.credit(bob, amount))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMixedCurrencies() {
        Account usdAccount = Account.open("Carol", AccountType.CUSTOMER, USD);

        assertThatThrownBy(() -> LedgerTransaction.create("ref-1", List.of(
                EntryDraft.debit(alice, 1_000),
                EntryDraft.credit(usdAccount, 1_000))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("currency");
    }

    @Test
    void rejectsBlankReference() {
        assertThatThrownBy(() -> LedgerTransaction.create("   ", List.of(
                EntryDraft.debit(alice, 1_000),
                EntryDraft.credit(bob, 1_000))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exposesEntriesAsUnmodifiableList() {
        LedgerTransaction tx = LedgerTransaction.create("ref-1", List.of(
                EntryDraft.debit(alice, 1_000),
                EntryDraft.credit(bob, 1_000)));

        assertThatThrownBy(() -> tx.getEntries().add(null))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsAmountsThatOverflow() {
        assertThatThrownBy(() -> LedgerTransaction.create("ref-1", List.of(
                EntryDraft.debit(alice, Long.MAX_VALUE),
                EntryDraft.debit(alice, 1),
                EntryDraft.credit(bob, 1))))
                .isInstanceOf(ArithmeticException.class);
    }
}