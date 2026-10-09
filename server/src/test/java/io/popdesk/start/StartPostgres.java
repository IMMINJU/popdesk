package io.popdesk.start;

import io.popdesk.TestcontainersConfiguration;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * One PostgreSQL 16 container for a test class that starts the app itself. Each test takes a new
 * empty database from it ({@link #newDatabase()}), so one container serves many first starts.
 * The test class closes it in {@code @AfterAll}.
 */
final class StartPostgres implements AutoCloseable {

    private static final AtomicInteger NEXT = new AtomicInteger();

    private final PostgreSQLContainer container;

    private StartPostgres(PostgreSQLContainer container) {
        this.container = container;
    }

    static StartPostgres start() {
        PostgreSQLContainer container = new PostgreSQLContainer(TestcontainersConfiguration.POSTGRES_IMAGE);
        container.start();
        return new StartPostgres(container);
    }

    /**
     * A new empty database owned by a new login role, as an installation gives the app: the role
     * may create tables in it. The role's password is unique, so a test can look for it in the log.
     */
    Database newDatabase() {
        int n = NEXT.incrementAndGet();
        String name = "start_" + n;
        String user = "popdesk_" + n;
        String password = "Db-Secret-" + n + "-" + UUID.randomUUID();
        superuser("postgres", "create role " + user + " login password '" + password + "'");
        superuser("postgres", "create database " + name + " owner " + user);
        return new Database(this, name, user, password);
    }

    /**
     * A new empty database the app's role may connect to but where it may not create anything: the
     * database and its {@code public} schema belong to the superuser, and nobody else has
     * {@code CREATE} on either.
     */
    Database newDatabaseWithoutCreate() {
        int n = NEXT.incrementAndGet();
        String name = "start_" + n;
        String user = "popdesk_nocreate_" + n;
        String password = "Db-Secret-" + n + "-" + UUID.randomUUID();
        superuser("postgres", "create role " + user + " login password '" + password + "'");
        superuser("postgres", "create database " + name);
        superuser("postgres", "revoke create on database " + name + " from public");
        superuser("postgres", "grant connect, temporary on database " + name + " to " + user);
        superuser(name, "revoke create on schema public from public");
        superuser(name, "grant usage on schema public to " + user);
        return new Database(this, name, user, password);
    }

    /**
     * A new empty database where the app's role has {@code CREATE} on the database but not on its
     * {@code public} schema, the common case on PostgreSQL 15 and later for a role that does not own
     * the database: the database and {@code public} belong to the superuser.
     */
    Database newDatabaseWithoutSchemaCreate() {
        int n = NEXT.incrementAndGet();
        String name = "start_" + n;
        String user = "popdesk_noschema_" + n;
        String password = "Db-Secret-" + n + "-" + UUID.randomUUID();
        superuser("postgres", "create role " + user + " login password '" + password + "'");
        superuser("postgres", "create database " + name);
        superuser("postgres", "grant connect, temporary, create on database " + name + " to " + user);
        superuser(name, "revoke create on schema public from public");
        superuser(name, "grant usage on schema public to " + user);
        return new Database(this, name, user, password);
    }

    String jdbcUrl(String database) {
        return "jdbc:postgresql://" + container.getHost() + ":" + container.getMappedPort(5432) + "/" + database;
    }

    /** A connection as the container's superuser to {@code database}, for looking in and for fixtures. */
    Connection superuserConnection(String database) throws SQLException {
        return DriverManager.getConnection(jdbcUrl(database), container.getUsername(), container.getPassword());
    }

    private void superuser(String database, String sql) {
        try (Connection c = superuserConnection(database); Statement s = c.createStatement()) {
            s.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not prepare the database: " + sql, e);
        }
    }

    @Override
    public void close() {
        container.stop();
    }
}
