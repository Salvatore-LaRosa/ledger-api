package dev.salvatore.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.salvatore.ledger.domain.account.Account;
import dev.salvatore.ledger.domain.account.AccountType;
import dev.salvatore.ledger.domain.exception.AccountNotFoundException;
import dev.salvatore.ledger.domain.exception.CurrencyMismatchException;
import dev.salvatore.ledger.domain.exception.InsufficientFundsException;
import dev.salvatore.ledger.domain.ledger.EntryDirection;
import dev.salvatore.ledger.domain.ledger.LedgerEntry;
import dev.salvatore.ledger.domain.ledger.LedgerTransaction;
import dev.salvatore.ledger.repository.AccountRepository;
import dev.salvatore.ledger.repository.LedgerTransactionRepository;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    private static final Currency EUR = Currency.getInstance("EUR");
    // UUID ordering is total: LOW_ID sorts before HIGH_ID.
    private static final UUID LOW_ID = new UUID(0L, 1L);
    private static final UUID HIGH_ID = new UUID(0L, 2L);

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private LedgerTransactionRepository ledgerTransactionRepository;

    private TransferService service;

    @BeforeEach
    void setUp() {
        service = new TransferService(accountRepository, ledgerTransactionRepository);
    }

    @Test
    void locksTheLowerIdFirstWhenTheSourceHasTheHigherId() {
        assertLocksLowerIdFirst(HIGH_ID, LOW_ID);
    }

    @Test
    void locksTheLowerIdFirstWhenTheSourceHasTheLowerId() {
        assertLocksLowerIdFirst(LOW_ID, HIGH_ID);
    }

    @Test
    void movesMoneyAndSavesABalancedTransaction() {
        Account source = account(AccountType.CUSTOMER, 10_000);
        Account destination = account(AccountType.MERCHANT, 0);
        stubLock(LOW_ID, source);
        stubLock(HIGH_ID, destination);
        stubSave();

        LedgerTransaction result = service.transfer("ref-1", LOW_ID, HIGH_ID, 2_500);

        assertThat(source.getBalance()).isEqualTo(7_500L);
        assertThat(destination.getBalance()).isEqualTo(2_500L);
        ArgumentCaptor<LedgerTransaction> captor = ArgumentCaptor.forClass(LedgerTransaction.class);
        verify(ledgerTransactionRepository).saveAndFlush(captor.capture());
        LedgerTransaction saved = captor.getValue();
        assertThat(result).isSameAs(saved);
        assertThat(saved.getReference()).isEqualTo("ref-1");
        assertThat(saved.getEntries())
                .extracting(LedgerEntry::getAccount, LedgerEntry::getDirection, LedgerEntry::getAmount)
                .containsExactlyInAnyOrder(
                        tuple(source, EntryDirection.DEBIT, 2_500L),
                        tuple(destination, EntryDirection.CREDIT, 2_500L));
    }

    @Test
    void insufficientFundsChangesNothingAndSavesNothing() {
        Account source = account(AccountType.CUSTOMER, 500);
        Account destination = account(AccountType.MERCHANT, 0);
        stubLock(LOW_ID, source);
        stubLock(HIGH_ID, destination);

        assertThatThrownBy(() -> service.transfer("ref-1", LOW_ID, HIGH_ID, 1_000))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(source.getBalance()).isEqualTo(500L);
        assertThat(destination.getBalance()).isZero();
        verify(ledgerTransactionRepository, never()).saveAndFlush(any(LedgerTransaction.class));
    }

    @Test
    void currencyMismatchIsRejectedBeforeAnythingChanges() {
        Account source = account(AccountType.CUSTOMER, 1_000);
        Account destination = Account.open("test-usd", AccountType.MERCHANT, Currency.getInstance("USD"));
        stubLock(LOW_ID, source);
        stubLock(HIGH_ID, destination);

        assertThatThrownBy(() -> service.transfer("ref-1", LOW_ID, HIGH_ID, 100))
                .isInstanceOf(CurrencyMismatchException.class);

        assertThat(source.getBalance()).isEqualTo(1_000L);
        assertThat(destination.getBalance()).isZero();
        verify(ledgerTransactionRepository, never()).saveAndFlush(any(LedgerTransaction.class));
    }

    @Test
    void unknownAccountIsRejected() {
        when(accountRepository.findByIdForUpdate(LOW_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.transfer("ref-1", LOW_ID, HIGH_ID, 100))
                .isInstanceOfSatisfying(AccountNotFoundException.class,
                        ex -> assertThat(ex.getAccountId()).isEqualTo(LOW_ID));

        verify(accountRepository, never()).findByIdForUpdate(HIGH_ID);
        verifyNoInteractions(ledgerTransactionRepository);
    }

    @Test
    void sameSourceAndDestinationIsRejectedWithoutLocking() {
        assertThatThrownBy(() -> service.transfer("ref-1", LOW_ID, LOW_ID, 100))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(accountRepository, ledgerTransactionRepository);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void nonPositiveAmountIsRejectedWithoutLocking(long amount) {
        assertThatThrownBy(() -> service.transfer("ref-1", LOW_ID, HIGH_ID, amount))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(accountRepository, ledgerTransactionRepository);
    }

    @Test
    void blankReferenceIsRejectedAndNothingIsSaved() {
        stubLock(LOW_ID, account(AccountType.CUSTOMER, 1_000));
        stubLock(HIGH_ID, account(AccountType.MERCHANT, 0));

        assertThatThrownBy(() -> service.transfer("   ", LOW_ID, HIGH_ID, 100))
                .isInstanceOf(IllegalArgumentException.class);

        verify(ledgerTransactionRepository, never()).saveAndFlush(any(LedgerTransaction.class));
    }

    private void assertLocksLowerIdFirst(UUID sourceId, UUID destinationId) {
        stubLock(sourceId, account(AccountType.CUSTOMER, 1_000));
        stubLock(destinationId, account(AccountType.MERCHANT, 0));
        stubSave();

        service.transfer("ref-1", sourceId, destinationId, 100);

        InOrder inOrder = inOrder(accountRepository);
        inOrder.verify(accountRepository).findByIdForUpdate(LOW_ID);
        inOrder.verify(accountRepository).findByIdForUpdate(HIGH_ID);
    }

    private void stubLock(UUID id, Account account) {
        when(accountRepository.findByIdForUpdate(id)).thenReturn(Optional.of(account));
    }

    private void stubSave() {
        when(ledgerTransactionRepository.saveAndFlush(any(LedgerTransaction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static Account account(AccountType type, long balance) {
        Account account = Account.open("test-" + type, type, EUR);
        if (balance > 0) {
            account.credit(balance);
        }
        return account;
    }
}