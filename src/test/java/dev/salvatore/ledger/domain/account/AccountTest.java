package dev.salvatore.ledger.domain.account;

import org.junit.jupiter.api.Test;

import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountTest {

    private static final Currency EUR = Currency.getInstance("EUR");

    @Test
    void openCreatesZeroBalanceAccountWithStrippedName() {
        Account account = Account.open("  Alice  ", AccountType.CUSTOMER, EUR);

        assertThat(account.getName()).isEqualTo("Alice");
        assertThat(account.getType()).isEqualTo(AccountType.CUSTOMER);
        assertThat(account.getCurrency()).isEqualTo(EUR);
        assertThat(account.getBalance()).isZero();
        assertThat(account.getCreatedAt()).isNotNull();
    }

    @Test
    void openRejectsBlankName() {
        assertThatThrownBy(() -> Account.open("   ", AccountType.CUSTOMER, EUR))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void openRejectsNullTypeOrCurrency() {
        assertThatThrownBy(() -> Account.open("Alice", null, EUR))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Account.open("Alice", AccountType.CUSTOMER, null))
                .isInstanceOf(NullPointerException.class);
    }
}