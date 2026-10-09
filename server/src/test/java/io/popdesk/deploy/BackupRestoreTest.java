package io.popdesk.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.deploy.AppSteps.Person;
import io.popdesk.support.Wait;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * {@code popdesk backup} and {@code popdesk restore} inside the app container (ADR D-46,
 * configuration.md "Commands"). One installation goes through the steps in order: back up while the
 * app runs, check the dump, try to restore while the app runs, into a database that is not empty,
 * into an empty database while someone holds the app's lock, and finally into an empty database
 * with no lock.
 *
 * <p>Restore runs in a new container of the app service, the way an operator runs it before the app
 * starts ({@code docker compose run --rm app popdesk restore <file>}, tasks.md section 7); the dump
 * is mounted into that container as one read-only file. The backup is copied out of the running
 * container with {@code docker compose cp}. The app's lock keys are read from {@code pg_locks}
 * while the app runs.
 */
@Tag("deploy")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BackupRestoreTest {

    private static final String DUMP_IN_APP = "/tmp/popdesk-test.dump";
    private static final Pattern DUMPED_BY = Pattern.compile("Dumped by pg_dump version: (\\d+)\\.");
    private static final Pattern DUMPED_FROM = Pattern.compile("Dumped from database version: (\\d+)\\.");

    /** The installation and what the test made in it before the backup. */
    record Installation(Stack stack, Db db, String companyCode, List<String> tables) {}

    /** The backup: the command's result, the dump copied to the host, audit rows around it. */
    record Backup(Cli.Result command, Path file, long auditBefore, long auditAfter) {}

    /** The app's single-instance lock: two int keys. */
    record LockKeys(int first, int second) {}

    private static final Once<Installation> INSTALLATION = new Once<>("starting an installation",
            BackupRestoreTest::install);
    private static final Once<Backup> BACKUP = new Once<>("popdesk backup", BackupRestoreTest::backup);
    private static LockKeys lockKeys;

    @AfterAll
    static void stop() {
        Installation installation = INSTALLATION.peek();
        if (installation != null) {
            installation.stack().close();
        }
    }

    @Test
    @Order(1)
    @DisplayName("S0-AC-89 popdesk backup writes a dump while the app runs, leaves the app healthy, and adds a "
            + "BACKUP_CREATED audit row")
    void backupWhileAppRuns() throws Exception {
        Installation installation = INSTALLATION.get();
        Backup backup = BACKUP.get();

        assertThat(backup.command().exit()).as("popdesk backup\n%s", backup.command()).isZero();
        assertThat(Files.size(backup.file())).as("size of the dump").isPositive();
        installation.stack().awaitHealthy("app", Duration.ofSeconds(30));
        assertThat(backup.auditAfter()).as("audit rows with BACKUP_CREATED").isEqualTo(backup.auditBefore() + 1);
    }

    @Test
    @Order(2)
    @DisplayName("S0-AC-89 pg_restore --list reads the dump, and its pg_dump has the server's major version")
    void dumpMatchesServer() {
        Installation installation = INSTALLATION.get();
        Stack stack = installation.stack();
        Backup backup = BACKUP.get();
        stack.command("cp", backup.file().toString(), "db:/tmp/check.dump").expectOk("copy the dump into db");

        Cli.Result list = stack.exec("db", null, "pg_restore", "--list", "/tmp/check.dump");

        assertThat(list.exit()).as("pg_restore --list\n%s", list).isZero();
        String serverMajor = installation.db().serverVersion().split("\\.")[0];
        assertThat(major(DUMPED_BY, list.out())).as("pg_dump major version in the dump header").isEqualTo(serverMajor);
        assertThat(major(DUMPED_FROM, list.out())).as("server major version in the dump header").isEqualTo(serverMajor);
        assertThat(serverMajor).as("server major version").isEqualTo("16");
    }

    @Test
    @Order(3)
    @DisplayName("S0-AC-89 popdesk restore fails while the app runs and holds its lock, and changes nothing")
    void restoreRefusedWhileAppRuns() {
        Installation installation = INSTALLATION.get();
        BACKUP.get();
        lockKeys = appLock(installation.db());
        Map<String, Long> before = installation.db().counts();

        Cli.Result restore = installation.stack().exec("app", null, "popdesk", "restore", DUMP_IN_APP);

        assertThat(restore.exit()).as("popdesk restore while the app runs\n%s", restore).isNotZero();
        assertThat(installation.db().counts()).as("rows per table").isEqualTo(before);
    }

    @Test
    @Order(4)
    @DisplayName("S0-AC-89 with the app stopped, popdesk restore into a database that is not empty fails and changes nothing")
    void restoreRefusedIntoNonEmptyDatabase() {
        Installation installation = INSTALLATION.get();
        Backup backup = BACKUP.get();
        installation.stack().command("stop", "app").expectOk("docker compose stop app");
        Wait.untilAsserted("the app's lock released", Duration.ofSeconds(30),
                () -> assertThat(appLocks(installation.db())).isEmpty());
        Map<String, List<String>> before = installation.db().snapshot();

        Cli.Result restore = restore(installation.stack(), backup.file());

        assertThat(restore.exit()).as("popdesk restore into a database with data\n%s", restore).isNotZero();
        assertThat(installation.db().snapshot()).as("every row").isEqualTo(before);
    }

    @Test
    @Order(5)
    @DisplayName("S0-AC-89 popdesk restore into an empty database fails while another session holds the app's lock, "
            + "and leaves it empty")
    void restoreRefusedWhileLockHeld() throws SQLException {
        Installation installation = INSTALLATION.get();
        Backup backup = BACKUP.get();
        assertThat(lockKeys).as("the app's lock keys, read while the app ran (step 3)").isNotNull();
        installation.stack().command("stop", "app").expectOk("docker compose stop app");
        emptyDatabase(installation);

        Cli.Result restore;
        try (Connection holder = installation.db().connect()) {
            Db.query(holder, "select pg_advisory_lock(?, ?)", lockKeys.first(), lockKeys.second());
            restore = restore(installation.stack(), backup.file());
        }

        assertThat(restore.exit()).as("popdesk restore while the lock is held\n%s", restore).isNotZero();
        assertThat(installation.db().userTableCount()).as("tables in the database").isZero();
    }

    @Test
    @Order(6)
    @DisplayName("S0-AC-89 popdesk restore into an empty database with no lock restores the dump and adds a "
            + "DATABASE_RESTORED audit row")
    void restoreIntoEmptyDatabase() {
        Installation installation = INSTALLATION.get();
        Backup backup = BACKUP.get();
        installation.stack().command("stop", "app").expectOk("docker compose stop app");
        if (installation.db().userTableCount() > 0) {
            emptyDatabase(installation);
        }

        Cli.Result restore = restore(installation.stack(), backup.file());

        assertThat(restore.exit()).as("popdesk restore into an empty database\n%s", restore).isZero();
        Db db = installation.db();
        assertThat(db.tables()).as("tables after restore").containsExactlyElementsOf(installation.tables());
        assertThat(db.rowsContaining(installation.companyCode())).as("rows with the company made before the backup")
                .isNotEmpty();
        assertThat(db.rowsContaining("DATABASE_RESTORED")).as("audit rows with DATABASE_RESTORED").hasSize(1);
    }

    private static Installation install() {
        Stack stack = Stack.release("backup");
        try {
            stack.up();
            stack.awaitHealthy("app");
            AppSteps steps = new AppSteps(stack);
            Person admin = steps.signInAdmin();
            String company = steps.createCompany(admin);
            Db db = stack.db();
            return new Installation(stack, db, company, db.tables());
        } catch (AssertionError | RuntimeException e) {
            stack.close();
            throw e;
        }
    }

    private static Backup backup() {
        Installation installation = INSTALLATION.get();
        Stack stack = installation.stack();
        long before = installation.db().rowsContaining("BACKUP_CREATED").size();
        Cli.Result command = stack.exec("app", null, "popdesk", "backup", DUMP_IN_APP);
        Path file = stack.work().resolve("backup.dump");
        if (command.ok()) {
            stack.command("cp", "app:" + DUMP_IN_APP, file.toString()).expectOk("copy the dump out of app");
        } else {
            throw new AssertionError("popdesk backup failed\n" + command);
        }
        file.toFile().setReadable(true, false);
        long after = installation.db().rowsContaining("BACKUP_CREATED").size();
        return new Backup(command, file, before, after);
    }

    private static Cli.Result restore(Stack stack, Path dump) {
        return stack.command(Duration.ofMinutes(5), "run", "--rm", "-T",
                "-v", dump.toAbsolutePath() + ":/tmp/restore.dump:ro", "app", "popdesk", "restore", "/tmp/restore.dump");
    }

    /** Removes db's volume and starts db again: a new, empty database. */
    private static void emptyDatabase(Installation installation) {
        Stack stack = installation.stack();
        String volume = dbVolume(stack);
        stack.command("rm", "-f", "-s", "db").expectOk("remove db");
        Cli.run(Duration.ofMinutes(1), Cli.docker("volume", "rm", volume)).expectOk("remove the volume " + volume);
        stack.up("db");
        installation.db().awaitReady();
        assertThat(installation.db().userTableCount()).as("tables in the new database").isZero();
    }

    /** The real name of the volume that holds db's files (the test override renamed it). */
    private static String dbVolume(Stack stack) {
        ComposeModel compose = ComposeModel.resolved(stack.config());
        return compose.mounts("db").stream()
                .filter(m -> m.target().equals("/var/lib/postgresql/data"))
                .map(m -> stack.project() + "-" + m.source().toLowerCase())
                .findFirst()
                .orElseThrow(() -> new AssertionError("db has no volume at /var/lib/postgresql/data"));
    }

    private static LockKeys appLock(Db db) {
        List<Map<String, Object>> locks = appLocks(db);
        assertThat(locks).as("granted advisory locks with two int keys while the app runs").hasSize(1);
        Map<String, Object> lock = locks.getFirst();
        return new LockKeys((int) ((Number) lock.get("classid")).longValue(),
                (int) ((Number) lock.get("objid")).longValue());
    }

    private static List<Map<String, Object>> appLocks(Db db) {
        return db.query("select classid::bigint as classid, objid::bigint as objid from pg_locks "
                + "where locktype = 'advisory' and objsubid = 2 and granted");
    }

    private static String major(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        if (!m.find()) {
            throw new AssertionError("No `" + pattern.pattern() + "` in pg_restore --list:\n" + Stack.tail(text));
        }
        return m.group(1);
    }
}
