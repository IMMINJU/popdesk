package io.popdesk;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Optional;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.internal.exception.FlywayMigrateException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.stereotype.Component;

/**
 * The first steps of a start (architecture.md section 7 "Start order"): take the single-instance
 * lock, check that the database user may create tables, refuse a schema that is newer than the app
 * or has a failed migration, then migrate. Spring Boot runs this before the web server opens its
 * ports, so health checks are refused while migrations run (S0-AC-09).
 *
 * <p>Each refusal logs one line saying what to do and then fails the start with an exception, which
 * stops the app the way any startup failure does.
 */
@Component
class MigrationStart implements FlywayMigrationStrategy {

    /** The recovery steps for a failed migration. */
    static final String RECOVERY_DOC = "docs/operate/upgrade.md";

    private static final Logger log = LoggerFactory.getLogger(MigrationStart.class);

    private final InstanceLock lock;
    private final DataSource dataSource;

    MigrationStart(InstanceLock lock, DataSource dataSource) {
        this.lock = lock;
        this.dataSource = dataSource;
    }

    @Override
    public void migrate(Flyway flyway) {
        lock.acquire();
        checkCreatePrivilege();
        checkHistory(flyway);
        try {
            flyway.migrate();
        } catch (FlywayException e) {
            String version = failedVersion(flyway, e).map(MigrationVersion::getVersion).orElse("(unknown)");
            String message = "Database migration " + version + " failed, so popdesk did not start. Follow the "
                    + "recovery steps in " + RECOVERY_DOC + " before starting again.";
            log.error(message);
            throw new IllegalStateException(message, e);
        }
    }

    /** The app's schema must let its user create tables, or the first migration fails halfway (S0-AC-10). */
    private void checkCreatePrivilege() {
        String user;
        String schema;
        boolean create;
        try (Connection c = dataSource.getConnection();
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("select current_user, current_schema(), "
                        + "coalesce(has_schema_privilege(current_schema(), 'CREATE'), false)")) {
            rs.next();
            user = rs.getString(1);
            schema = rs.getString(2);
            create = rs.getBoolean(3);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not check the database user's privileges (SQL state "
                    + e.getSQLState() + ").", e);
        }
        if (!create) {
            String message = schema == null
                    ? "The database user " + user + " has no schema to create tables in: no schema on its search_path "
                            + "exists. Grant it a schema with CREATE (for example GRANT CREATE ON SCHEMA public TO "
                            + user + ") and start again. popdesk did not run any migration."
                    : "The database user " + user + " lacks the CREATE privilege on schema " + schema
                            + ", so popdesk cannot create its tables. Grant it (GRANT CREATE ON SCHEMA " + schema
                            + " TO " + user + ") and start again. popdesk did not run any migration.";
            log.error(message);
            throw new IllegalStateException(message);
        }
    }

    /** A migration newer than the app (S0-AC-07) or one that failed (S0-AC-08) stops the start. */
    private static void checkHistory(Flyway flyway) {
        MigrationInfo[] all = flyway.info().all();
        Optional<MigrationInfo> newer = Arrays.stream(all)
                .filter(i -> i.getState() == MigrationState.FUTURE_SUCCESS || i.getState() == MigrationState.FUTURE_FAILED)
                .max(Comparator.comparing(MigrationInfo::getVersion));
        if (newer.isPresent()) {
            String known = Arrays.stream(all).filter(i -> i.getState().isResolved() && i.getVersion() != null)
                    .map(MigrationInfo::getVersion).max(Comparator.naturalOrder())
                    .map(MigrationVersion::getVersion).orElse("none");
            String message = "The database has migration " + newer.get().getVersion().getVersion()
                    + ", newer than this popdesk version knows (latest " + known + "). popdesk does not start on a "
                    + "newer schema: restore the backup you took before the upgrade, or run the newer version.";
            log.error(message);
            throw new IllegalStateException(message);
        }
        Optional<MigrationInfo> failed = Arrays.stream(all).filter(i -> i.getState().isFailed()).findFirst();
        if (failed.isPresent()) {
            String message = "Database migration " + versionOf(failed.get()) + " failed earlier and is still marked "
                    + "failed, so popdesk did not start. Follow the recovery steps in " + RECOVERY_DOC + ".";
            log.error(message);
            throw new IllegalStateException(message);
        }
    }

    private static Optional<MigrationVersion> failedVersion(Flyway flyway, FlywayException e) {
        if (e instanceof FlywayMigrateException m && m.getMigration() != null && m.getMigration().getVersion() != null) {
            return Optional.of(m.getMigration().getVersion());
        }
        try {
            return Arrays.stream(flyway.info().all()).filter(i -> i.getState().isFailed())
                    .map(MigrationInfo::getVersion).findFirst();
        } catch (FlywayException again) {
            log.warn("Could not read the migration history after the failure ({})", again.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private static String versionOf(MigrationInfo info) {
        return info.getVersion() == null ? info.getDescription() : info.getVersion().getVersion();
    }
}
