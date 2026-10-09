package io.popdesk.helpdesk.account;

import io.popdesk.helpdesk.AccountStatus;
import io.popdesk.helpdesk.Role;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The writes of the first start: team 1 and the first admin (ACCT-5, S0-AC-01). */
@Repository
class FirstAdminRepository {

    /** The one team until there are more (ADR D-18). */
    static final long TEAM = 1;

    private final JdbcClient jdbc;

    FirstAdminRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insertTeamIfMissing(String name, Instant now) {
        jdbc.sql("insert into tenant (id, name, created_at) values (?, ?, ?) on conflict (id) do nothing")
                .params(TEAM, name, Timestamp.from(now))
                .update();
    }

    boolean adminExists() {
        return jdbc.sql("select exists (select 1 from account where role = ?)")
                .param(Role.ADMIN.name())
                .query(Boolean.class)
                .single();
    }

    /** Inserts an ACTIVE admin when the database has no admin; returns its ID, or empty when one exists. */
    Optional<Long> insertAdminIfNone(String email, String name, String passwordHash, String locale, Instant now) {
        return jdbc.sql("insert into account (tenant_id, email, name, role, status, password_hash, locale, "
                        + "created_at, updated_at) select ?, ?, ?, ?, ?, ?, ?, ?, ? "
                        + "where not exists (select 1 from account where role = ?) returning id")
                .params(TEAM, email, name, Role.ADMIN.name(), AccountStatus.ACTIVE.name(), passwordHash, locale,
                        Timestamp.from(now), Timestamp.from(now), Role.ADMIN.name())
                .query(Long.class)
                .optional();
    }
}
