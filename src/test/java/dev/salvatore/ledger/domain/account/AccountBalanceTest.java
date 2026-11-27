package dev.salvatore.ledger.domain.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.salvatore.ledger.domain.exception.InsufficientFundsException;
import java.util.Currency;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class AccountBalanceTest {

    private static final Currency EUR = Currency.getInstance("EUR");

    @Test
    void creditIncreasesTheBalance() {
        Account account = account(AccountType.CUSTOMER);

        account.credit(1_000);
        account.credit(500);

        assertThat(account.getBalance()).isEqualTo(1_500L);
    }

    @Test
    void debitDecreasesTheBalance() {
        Account account = account(AccountType.CUSTOMER);
        account.credit(1_000);

        account.debit(400);

        assertThat(account.getBalance()).isEqualTo(600L);
    }

    @Test
    void debitDownToExactlyZeroIsAllowed() {
        Account account = account(AccountType.CUSTOMER);
        account.credit(500);

        account.debit(500);

        assertThat(account.getBalance()).isZero();
    }

    @ParameterizedTest
    @EnumSource(value = AccountType.class, names = {"CUSTOMER", "MERCHANT", "TRANSIT"})
    void debitBeyondTheBalanceIsRejectedAndLeavesTheBalanceUnchanged(AccountType type) {
        Account account = account(type);
        account.credit(500);

        assertThatThrownBy(() -> account.debit(600))
                .isInstanceOfSatisfying(InsufficientFundsException.class, ex -> {
                    assertThat(ex.getBalance()).isEqualTo(500L);
                    assertThat(ex.getRequested()).isEqualTo(600L);
                });
        assertThat(account.getBalance()).isEqualTo(500L);
    }

    @Test
    void externalAccountMayGoNegative() {
        Account external = account(AccountType.EXTERNAL);

        external.debit(5_000);

        assertThat(external.getBalance()).isEqualTo(-5_000L);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void nonPositiveAmountsAreRejected(long amount) {
        Account account = account(AccountType.CUSTOMER);

        assertThatThrownBy(() -> account.credit(amount)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> account.debit(amount)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void overflowIsRejectedAndLeavesTheBalanceUnchanged() {
        Account account = account(AccountType.CUSTOMER);
        account.credit(Long.MAX_VALUE);

        assertThatThrownBy(() -> account.credit(1)).isInstanceOf(ArithmeticException.class);

        assertThat(account.getBalance()).isEqualTo(Long.MAX_VALUE);
    }

    private static Account account(AccountType type) {
        return Account.open("test-" + type, type, EUR);
    }
}