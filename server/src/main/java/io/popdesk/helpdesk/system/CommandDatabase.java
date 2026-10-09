package io.popdesk.helpdesk.system;

import io.popdesk.SecretFiles;
import io.popdesk.helpdesk.system.PopdeskCommand.Failed;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import tools.jackson.databind.json.JsonMapper;

/**
 * The database as the {@code popdesk} commands see it: the app's own settings ({@code POPDESK_DB_URL},
 * {@code POPDESK_DB_USER}, {@code POPDESK_DB_PASSWORD} or {@code POPDESK_DB_PASSWORD_FILE}) read from
 * the container's environment, a JDBC connection, and the same settings for the PostgreSQL tools
 * ({@code pg_dump}, {@code pg_restore}) as libpq environment variables, so the password is never on a
 * command line.
 */
final class CommandDatabase {

    /** The default of {@code spring.datasource.url} in application.yml. */
    static final String DEFAULT_URL = "jdbc:postgresql://localhost:5432/popdesk?logServerErrorDetail=false";
    static final String DEFAULT_USER = "popdesk";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String url;
    private final String user;
    private final String password;

    private CommandDatabase(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
    }

    static CommandDatabase fromEnvironment(Map<String, String> env) {
        String url = valueOr(env.get("POPDESK_DB_URL"), DEFAULT_URL);
        String user = valueOr(env.get("POPDESK_DB_USER"), DEFAULT_USER);
        String password = env.get("POPDESK_DB_PASSWORD");
        String file = env.get("POPDESK_DB_PASSWORD_FILE");
        if (file != null && !file.isEmpty()) {
            if (password != null) {
                throw new Failed("Both POPDESK_DB_PASSWORD and POPDESK_DB_PASSWORD_FILE are set. Set only one of them.");
            }
            try {
                password = SecretFiles.cutOneLineBreak(Files.readString(Path.of(file), StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new Failed("Could not read the file that POPDESK_DB_PASSWORD_FILE names ("
                        + e.getClass().getSimpleName() + ").");
            }
        }
        return new CommandDatabase(url, user, password);
    }

    private static String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    Connection connect() {
        Properties properties = new Properties();
        properties.setProperty("user", user);
        if (password != null) {
            properties.setProperty("password", password);
        }
        properties.setProperty("ApplicationName", "popdesk command");
        try {
            Connection c = DriverManager.getConnection(url, properties);
            c.setAutoCommit(true);
            return c;
        } catch (SQLException e) {
            throw new Failed("Could not connect to the database (SQL state " + e.getSQLState()
                    + "). Check POPDESK_DB_URL, POPDESK_DB_USER and POPDESK_DB_PASSWORD.");
        }
    }

    /** The database name, for {@code pg_restore --dbname}. */
    String databaseName() {
        return target().database();
    }

    /**
     * Runs a PostgreSQL tool with the connection in libpq's environment variables. Its output goes to
     * this command's output. Returns its exit code.
     */
    int runTool(List<String> command) {
        Target target = target();
        ProcessBuilder builder = new ProcessBuilder(command).inheritIO();
        Map<String, String> env = builder.environment();
        env.put("PGHOST", target.host());
        env.put("PGPORT", Integer.toString(target.port()));
        env.put("PGDATABASE", target.database());
        env.put("PGUSER", user);
        env.put("PGAPPNAME", "popdesk command");
        if (password != null) {
            env.put("PGPASSWORD", password);
        }
        if (target.sslMode() != null) {
            env.put("PGSSLMODE", target.sslMode());
        }
        try {
            return builder.start().waitFor();
        } catch (IOException e) {
            throw new Failed("Could not run " + command.getFirst() + " (" + e.getClass().getSimpleName() + ").");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Failed(command.getFirst() + " was interrupted.");
        }
    }

    /**
     * Writes an audit row whose person is popdesk itself ({@code {accountId: 0, name: null, email:
     * null}}), as {@code AuditLog.recordBySystem} does in the app, on the caller's connection and in its
     * transaction.
     */
    static void auditBySystem(Connection c, Clock clock, String action, Map<String, ?> target,
            Map<String, ?> changes) throws SQLException {
        Map<String, Object> person = new LinkedHashMap<>();
        person.put("accountId", 0);
        person.put("name", null);
        person.put("email", null);
        try (PreparedStatement s = c.prepareStatement("insert into audit_log (tenant_id, action, actor, target, "
                + "changes, occurred_at) values (1, ?, cast(? as jsonb), cast(? as jsonb), cast(? as jsonb), ?)")) {
            s.setString(1, action);
            s.setString(2, JSON.writeValueAsString(person));
            s.setString(3, target == null ? null : JSON.writeValueAsString(target));
            s.setString(4, changes == null ? null : JSON.writeValueAsString(changes));
            s.setTimestamp(5, Timestamp.from(clock.instant().truncatedTo(ChronoUnit.MICROS)));
            s.executeUpdate();
        }
    }

    /** Where the JDBC URL points: one host, as the Compose file and configuration.md give it. */
    record Target(String host, int port, String database, String sslMode) {}

    Target target() {
        if (!url.startsWith("jdbc:postgresql://")) {
            throw new Failed("POPDESK_DB_URL is not a jdbc:postgresql:// URL.");
        }
        URI uri;
        try {
            uri = URI.create(url.substring("jdbc:".length()));
        } catch (IllegalArgumentException e) {
            throw new Failed("POPDESK_DB_URL is not a URL the commands can read.");
        }
        if (uri.getHost() == null) {
            throw new Failed("POPDESK_DB_URL names no single host; the commands need one.");
        }
        String path = uri.getPath() == null ? "" : uri.getPath().replaceFirst("^/", "");
        String sslMode = null;
        if (uri.getQuery() != null) {
            for (String pair : uri.getQuery().split("&")) {
                if (pair.startsWith("sslmode=")) {
                    sslMode = pair.substring("sslmode=".length());
                }
            }
        }
        return new Target(uri.getHost(), uri.getPort() < 0 ? 5432 : uri.getPort(), path.isEmpty() ? user : path,
                sslMode);
    }
}
