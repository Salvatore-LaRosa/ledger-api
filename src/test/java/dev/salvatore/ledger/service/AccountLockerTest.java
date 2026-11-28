package dev.salvatore.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.salvatore.ledger.domain.account.Account;
import dev.salvatore.ledger.domain.account.AccountType;
import dev.salvatore.ledger.domain.exception.AccountNotFoundException;
import dev.salvatore.ledger.repository.AccountRepository;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AccountLockerTest {

    // UUID ordering is total: LOW_ID sorts before HIGH_ID.
    private static final UUID LOW_ID = new UUID(0L, 1L);
    private static final UUID HIGH_ID = new UUID(0L, 2L);

    @Mock
    private AccountRepository accountRepository;

    private AccountLocker locker;

    @BeforeEach
    void setUp() {
        locker = new AccountLocker(accountRepository);
    }

    @Test
    void locksInAscendingIdOrderWhateverTheOrderOfTheInput() {
        Account low = account();
        Account high = account();
        when(accountRepository.findByIdForUpdate(LOW_ID)).thenReturn(Optional.of(low));
        when(accountRepository.findByIdForUpdate(HIGH_ID)).thenReturn(Optional.of(high));

        Map<UUID, Account> locked = locker.lockAll(List.of(HIGH_ID, LOW_ID));

        InOrder inOrder = inOrder(accountRepository);
        inOrder.verify(accountRepository).findByIdForUpdate(LOW_ID);
        inOrder.verify(accountRepository).findByIdForUpdate(HIGH_ID);
        assertThat(locked).containsEntry(LOW_ID, low).containsEntry(HIGH_ID, high);
    }

    @Test
    void anAccountListedTwiceIsLockedOnce() {
        when(accountRepository.findByIdForUpdate(LOW_ID)).thenReturn(Optional.of(account()));

        locker.lockAll(List.of(LOW_ID, LOW_ID));

        verify(accountRepository, times(1)).findByIdForUpdate(LOW_ID);
    }

    @Test
    void unknownAccountIsRejected() {
        when(accountRepository.findByIdForUpdate(LOW_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> locker.lockAll(List.of(LOW_ID)))
                .isInstanceOfSatisfying(AccountNotFoundException.class,
                        ex -> assertThat(ex.getAccountId()).isEqualTo(LOW_ID));
    }

    private static Account account() {
        return Account.open("test", AccountType.CUSTOMER, Currency.getInstance("EUR"));
    }
}