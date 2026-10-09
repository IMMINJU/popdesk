package io.popdesk.helpdesk.account;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The IDs of the server sessions in Spring Session's table {@code spring_session}. Spring Session reads
 * and deletes a session by ID; it has no way to list them.
 */
@Repository
class SessionTableRepository {

    private final JdbcClient jdbc;

    SessionTableRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<String> listSessionIds() {
        return jdbc.sql("select session_id from spring_session order by primary_id").query(String.class).list();
    }
}
