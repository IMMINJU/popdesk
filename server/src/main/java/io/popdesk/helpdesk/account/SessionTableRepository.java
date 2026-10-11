package io.popdesk.helpdesk.account;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The server sessions in Spring Session's table {@code spring_session}. Spring Session reads and
 * deletes a session by ID; it has no way to list them, and none to delete those of one account at once.
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

    /**
     * Deletes the sessions whose indexed principal is {@code principalName}, which {@link Sessions} sets
     * to the account ID; their attributes go with them. Returns how many.
     */
    int deleteByPrincipalName(String principalName) {
        return jdbc.sql("delete from spring_session where principal_name = ?").param(principalName).update();
    }
}
