package io.popdesk.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.deploy.AppSteps.Person;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * {@code popdesk restore} checks for an empty database with what the database holds, not with what
 * its own user may see (verify.md V-10). A table that belongs to another role, on which the app's
 * database user has no privilege, does not show in {@code information_schema.tables} for that user;
 * the database is still not empty, and restore must refuse it (configuration.md "Commands": "the
 * database has any table").
 *
 * <p>The restore runs as a role that owns the database but is not a superuser, so it could restore
 * the dump; the table belongs to the superuser. After the refusal the table is dropped and the same
 * restore must work, which shows the table was the only thing in its way.
 */
@Tag("deploy")
class RestoreUnseenTableTest {

    private static final String DUMP_IN_APP = "/tmp/popdesk-test.dump";
    private static final String RESTORE_USER = "test_restore_owner";
    private static final String RESTORE_PASSWORD = "Restore-Password-1";
    private static final String OTHERS_TABLE = "test_owned_by_another_role";

    @Test
    @DisplayName("V-10 S0-AC-89 popdesk restore refuses a database whose only table belongs to another role and is "
            + "invisible to the app's database user, and changes nothing")
    void restoreRefusedWhenTheOnlyTableIsUnseen() {
        Stack stack = Stack.release("restore-unseen");
        try {
            Path dump = backupOfNewInstallation(stack);
            Db db = stack.db();
            stack.command("stop", "app").expectOk("docker compose stop app");
            emptyDatabase(stack, db);
            db.execute("create role " + RESTORE_USER + " login password '" + RESTORE_PASSWORD + "'");
            db.execute("do $$ begin execute format('alter database %I owner to " + RESTORE_USER
                    + "', current_database()); end $$");
            db.execute("create table public." + OTHERS_TABLE + " (id bigint primary key)");
            assertThat(tablesSeenBy(db, RESTORE_USER)).as("tables %s sees in information_schema.tables", RESTORE_USER)
                    .isEmpty();
            assertThat(allTables(db)).as("tables in the database").containsExactly(OTHERS_TABLE);

            Cli.Result refused = restore(stack, dump);

            assertThat(refused.exit()).as("popdesk restore into a database with another role's table\n%s", refused)
                    .isNotZero();
            assertThat(allTables(db)).as("tables in the database after the refused restore")
                    .containsExactly(OTHERS_TABLE);

            db.execute("drop table public." + OTHERS_TABLE);
            Cli.Result restored = restore(stack, dump);

            assertThat(restored.exit()).as("the same popdesk restore once the table is gone\n%s", restored).isZero();
            assertThat(db.rowsContaining("DATABASE_RESTORED")).as("audit rows with DATABASE_RESTORED").hasSize(1);
        } catch (RuntimeException e) {
            throw new AssertionError("The restore test could not go on: " + e, e);
        } finally {
            stack.close();
        }
    }

    /** Starts an installation, makes a company in it, and copies a {@code popdesk backup} dump to the host. */
    private static Path backupOfNewInstallation(Stack stack) {
        stack.up();
        stack.awaitHealthy("app");
        AppSteps steps = new AppSteps(stack);
        Person admin = steps.signInAdmin();
        steps.createCompany(admin);
        stack.exec("app", null, "popdesk", "backup", DUMP_IN_APP).expectOk("popdesk backup");
        Path file = stack.work().resolve("backup.dump");
        stack.command("cp", "app:" + DUMP_IN_APP, file.toString()).expectOk("copy the dump out of app");
        file.toFile().setReadable(true, false);
        return file;
    }

    /** {@code popdesk restore} in a new container of the app service, as the role that owns the database. */
    private static Cli.Result restore(Stack stack, Path dump) {
        return stack.command(Duration.ofMinutes(5), "run", "--rm", "-T",
                "-e", "POPDESK_DB_USER=" + RESTORE_USER,
                "-e", "POPDESK_DB_PASSWORD=" + RESTORE_PASSWORD,
                "-v", dump.toAbsolutePath() + ":/tmp/restore.dump:ro", "app", "popdesk", "restore", "/tmp/restore.dump");
    }

    /** Removes db's volume and starts db again: a new, empty database. */
    private static void emptyDatabase(Stack stack, Db db) {
        String volume = dbVolume(stack);
        stack.command("rm", "-f", "-s", "db").expectOk("remove db");
        Cli.run(Duration.ofMinutes(1), Cli.docker("volume", "rm", volume)).expectOk("remove the volume " + volume);
        stack.up("db");
        db.awaitReady();
        assertThat(allTables(db)).as("tables in the new database").isEmpty();
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

    /** Every ordinary table outside the system schemas, read from the catalog as the superuser. */
    private static List<String> allTables(Db db) {
        return db.query("select c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace "
                + "where c.relkind in ('r', 'p') and n.nspname not in ('pg_catalog', 'information_schema') "
                + "and n.nspname not like 'pg_toast%' order by c.relname")
                .stream().map(r -> (String) r.get("relname")).toList();
    }

    /** What {@code information_schema.tables} shows to {@code role}: only tables it owns or has a privilege on. */
    private static List<String> tablesSeenBy(Db db, String role) {
        return db.query("select c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace "
                + "where c.relkind in ('r', 'p') and n.nspname not in ('pg_catalog', 'information_schema') "
                + "and n.nspname not like 'pg_toast%' and (pg_has_role(?, c.relowner, 'USAGE') "
                + "or has_table_privilege(?, c.oid, 'SELECT, INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER') "
                + "or has_any_column_privilege(?, c.oid, 'SELECT, INSERT, UPDATE, REFERENCES'))", role, role, role)
                .stream().map(r -> (String) r.get("relname")).toList();
    }
}
