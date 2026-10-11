package io.popdesk.helpdesk.system;

import io.popdesk.InstanceLock;
import io.popdesk.helpdesk.audit.AuditAction;
import io.popdesk.helpdesk.system.PopdeskCommand.Failed;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.List;
import java.util.Map;

/**
 * {@code popdesk restore <file>} (S0-AC-89, D-46): restores a dump from {@code popdesk backup} into an
 * empty database before the app starts. Run it in a new container of the app service, with the dump
 * mounted as one file ({@code docker compose run --rm -v ./x.dump:/tmp/x.dump:ro app popdesk restore
 * /tmp/x.dump}).
 *
 * <p>It takes the app's single-instance lock ({@link InstanceLock}'s two keys) without waiting and
 * keeps it until it is done, so the app cannot start on a half-restored database. When the lock is
 * taken (the app runs) or the database has any table, it changes nothing and fails. The restore runs
 * in one transaction, then a {@code DATABASE_RESTORED} audit row goes into the restored database.
 */
final class RestoreCommand {

    private final CommandDatabase database;
    private final Clock clock;

    RestoreCommand(CommandDatabase database, Clock clock) {
        this.database = database;
        this.clock = clock;
    }

    void run(String file) throws SQLException {
        if (!Files.isReadable(Path.of(file))) {
            throw new Failed("Cannot read " + file + ". Mount the dump into the container, for example with "
                    + "docker compose run --rm -v ./backup.dump:" + file + ":ro app popdesk restore " + file);
        }
        try (Connection c = database.connect()) {
            if (!tryLock(c)) {
                throw new Failed("The app is running on this database (it holds the single-instance lock). Stop the "
                        + "app first: restore only goes into an empty database before the app starts.");
            }
            long tables = tableCount(c);
            if (tables > 0) {
                throw new Failed("The database is not empty (" + tables + " tables). Restore only goes into a new, "
                        + "empty database; nothing was changed.");
            }
            int exit = database.runTool(List.of("pg_restore", "--exit-on-error", "--single-transaction",
                    "--no-owner", "--no-privileges", "--no-password", "--dbname=" + database.databaseName(), file));
            if (exit != 0) {
                throw new Failed("pg_restore ended with exit code " + exit + "; it ran in one transaction, so the "
                        + "database is still empty.");
            }
            CommandDatabase.auditBySystem(c, clock, AuditAction.DATABASE_RESTORED, Map.of("type", "DATABASE"),
                    Map.of("file", file));
            System.out.println("Restored " + file + ". Start the app now.");
        }
    }

    private static boolean tryLock(Connection c) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("select pg_try_advisory_lock(?, ?)")) {
            s.setInt(1, InstanceLock.APP_KEY);
            s.setInt(2, InstanceLock.INSTANCE_KEY);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    /**
     * Tables outside the system schemas, counted in the catalog: {@code information_schema.tables} shows
     * only the tables this user owns or has a privilege on, and another role's table makes the database
     * not empty all the same. Schema names that begin with {@code pg_} belong to the system (the
     * catalog, TOAST, other sessions' temporary tables).
     */
    private static long tableCount(Connection c) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("select count(*) from pg_class c "
                + "join pg_namespace n on n.oid = c.relnamespace where c.relkind in ('r', 'p') "
                + "and n.nspname <> 'information_schema' and n.nspname !~ '^pg_'")) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
