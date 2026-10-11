package io.popdesk.helpdesk.system;

import io.popdesk.helpdesk.audit.AuditAction;
import io.popdesk.helpdesk.system.PopdeskCommand.Failed;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code popdesk backup <file>} (S0-AC-89): a {@code pg_dump} custom-format dump of the app's
 * database, written while the app runs. It takes no lock of the app's. The {@code pg_dump} in the image
 * must have the server's major version; otherwise the command stops before dumping. A dump that
 * worked gets a {@code BACKUP_CREATED} audit row.
 */
final class BackupCommand {

    private static final Pattern VERSION = Pattern.compile("\\b(\\d+)\\.\\d+");

    private final CommandDatabase database;
    private final Clock clock;

    BackupCommand(CommandDatabase database, Clock clock) {
        this.database = database;
        this.clock = clock;
    }

    void run(String file) throws SQLException {
        try (Connection c = database.connect()) {
            int server = serverMajor(c);
            int tool = toolMajor();
            if (server != tool) {
                throw new Failed("pg_dump in this image is version " + tool + " but the database server is version "
                        + server + ". Use the popdesk image that matches the server's major version.");
            }
            int exit = database.runTool(List.of("pg_dump", "--format=custom", "--no-password", "--file=" + file));
            if (exit != 0) {
                throw new Failed("pg_dump ended with exit code " + exit + "; no backup was written.");
            }
            CommandDatabase.auditBySystem(c, clock, AuditAction.BACKUP_CREATED, Map.of("type", "DATABASE"),
                    Map.of("file", file));
            System.out.println("Wrote the backup to " + file + ".");
        }
    }

    static int serverMajor(Connection c) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("show server_version_num")) {
            rs.next();
            return Integer.parseInt(rs.getString(1).strip()) / 10000;
        }
    }

    private static int toolMajor() {
        Process process;
        String out;
        try {
            process = new ProcessBuilder("pg_dump", "--version").redirectErrorStream(true).start();
            out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            process.waitFor();
        } catch (IOException e) {
            throw new Failed("Could not run pg_dump (" + e.getClass().getSimpleName() + ").");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Failed("pg_dump --version was interrupted.");
        }
        Matcher m = VERSION.matcher(out);
        if (process.exitValue() != 0 || !m.find()) {
            throw new Failed("Could not read the version of pg_dump.");
        }
        return Integer.parseInt(m.group(1));
    }
}
