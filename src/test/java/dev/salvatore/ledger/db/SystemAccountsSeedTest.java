package dev.salvatore.ledger.db;

import static org.assertj.core.api.Assertions.assertThat;

import dev.salvatore.ledger.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SystemAccountsSeedTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void exactlyOneEuroTransitAccountIsSeeded() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM accounts WHERE type = 'TRANSIT' AND currency = 'EUR'",
                Integer.class);

        assertThat(count).isEqualTo(1);
    }
}