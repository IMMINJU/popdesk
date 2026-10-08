package io.popdesk;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PopdeskApplicationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void startsAgainstPostgres16() {
        String version = jdbc.queryForObject("show server_version", String.class);

        assertThat(version).startsWith("16.");
    }
}
