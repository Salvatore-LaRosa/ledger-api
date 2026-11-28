package dev.salvatore.ledger.repository;

import static org.assertj.core.api.Assertions.assertThat;

import dev.salvatore.ledger.TestcontainersConfiguration;
import dev.salvatore.ledger.domain.account.Account;
import dev.salvatore.ledger.domain.account.AccountType;
import dev.salvatore.ledger.domain.ledger.LedgerEntry;
import dev.salvatore.ledger.domain.ledger.LedgerTransaction;
import dev.salvatore.ledger.service.TransferService;
import jakarta.persistence.EntityManager;
import java.util.Currency;
import java.util.UUID;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

/**
 * Guards an assumption the reversal relies on: reading the account id of an entry must not
 * load the account. If it did, the account would be read before being locked, and the
 * FOR UPDATE query would not refresh that stale state.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class LedgerEntryLazyAccountTest {

    private static final Currency XTS = Currency.getInstance("XTS");

    @Autowired
    private TransferService transferService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private LedgerTransactionRepository ledgerTransactionRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void readingTheAccountIdOfAnEntryDoesNotLoadTheAccount() {
        Account external = accountRepository.save(Account.open("lazy-ext-" + UUID.randomUUID(), AccountType.EXTERNAL, XTS));
        Account customer = accountRepository.save(Account.open("lazy-cus-" + UUID.randomUUID(), AccountType.CUSTOMER, XTS));
        LedgerTransaction saved = transferService.transfer(
                "lazy:" + UUID.randomUUID(), external.getId(), customer.getId(), 100);
        entityManager.flush();
        entityManager.clear();

        LedgerTransaction loaded = ledgerTransactionRepository.findById(saved.getId()).orElseThrow();

        assertThat(loaded.getEntries()).hasSize(2);
        for (LedgerEntry entry : loaded.getEntries()) {
            assertThat(entry.getAccount().getId()).isNotNull();
            assertThat(Hibernate.isInitialized(entry.getAccount())).isFalse();
        }
    }
}