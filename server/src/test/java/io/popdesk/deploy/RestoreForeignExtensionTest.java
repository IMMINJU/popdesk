package io.popdesk.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.deploy.AppSteps.Person;
import io.popdesk.support.ApiResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * {@code popdesk restore} into a database where an administrator created the {@code pg_trgm}
 * extension for the app (ADR D-46, S3-AC-93): the app's database user owns the database, as in
 * {@link RestoreUnseenTableTest}, but is not a superuser and does not own the extension. The dump of
 * an installation of this slice holds the extension and its comment; only the extension's owner may
 * set that comment, so a restore that replays it fails as a whole.
 *
 * <p>One installation goes through the steps: two issues are filed, the backup is taken, the
 * database is replaced by an empty one with the extension and the restricted role, the dump is
 * restored as that role, and the app is started on it as that role.
 */
@Tag("deploy")
class RestoreForeignExtensionTest {

    private static final String DUMP_IN_APP = "/tmp/popdesk-test.dump";
    private static final String RESTORE_USER = "test_restore_not_extension_owner";
    private static final String RESTORE_PASSWORD = "Restore-Password-1";

    @Test
    @DisplayName("S3-AC-93 popdesk restore finishes in an empty database where a superuser created pg_trgm and the "
            + "app's database user does not own it; the database then has the extension and a gin_trgm_ops index, "
            + "and the app started on it finds an issue by its title")
    void restoreWhereAnotherRoleOwnsTheExtension() {
        Stack stack = Stack.release("restore-trgm");
        try {
            stack.up();
            stack.awaitHealthy("app");
            AppSteps steps = new AppSteps(stack);
            Person admin = steps.signInAdmin();
            String company = steps.createCompany(admin);
            Person customer = steps.join(admin, "CUSTOMER", List.of(company), null);
            // Two titles that end in consecutive numbers: neither is a part of the other.
            String searched = steps.fileIssue(customer, company);
            steps.fileIssue(customer, company);
            String title = admin.client().get("/api/console/issues/" + searched).expect(200).jsonText("title");
            Path dump = backup(stack);
            Db db = stack.db();
            stack.command("stop", "app").expectOk("docker compose stop app");
            emptyDatabase(stack, db);
            db.execute("create extension pg_trgm");
            db.execute("create role " + RESTORE_USER + " login password '" + RESTORE_PASSWORD + "'");
            db.execute("do $$ begin execute format('alter database %I owner to " + RESTORE_USER
                    + "', current_database()); end $$");
            assertThat(db.count("select count(*) from pg_roles where rolname = ? and not rolsuper", RESTORE_USER))
                    .as("role %s that is not a superuser", RESTORE_USER).isEqualTo(1);
            assertThat(extensionOwners(db)).as("owners of pg_trgm before the restore").hasSize(1)
                    .doesNotContain(RESTORE_USER);

            Cli.Result restored = restore(stack, dump);

            assertThat(restored.exit()).as("popdesk restore as a role that does not own pg_trgm\n%s", restored)
                    .isZero();
            assertThat(extensionOwners(db)).as("owners of pg_trgm after the restore").hasSize(1)
                    .doesNotContain(RESTORE_USER);
            assertThat(db.query("select indexname from pg_indexes where indexdef like '%gin_trgm_ops%'"))
                    .as("indexes with gin_trgm_ops after the restore").isNotEmpty();

            stack.appEnv("POPDESK_DB_USER", RESTORE_USER).appEnv("POPDESK_DB_PASSWORD", RESTORE_PASSWORD);
            stack.up("app");
            stack.awaitHealthy("app");
            ApiResponse found = new AppSteps(stack).signInAdmin().client().get("/api/console/issues")
                    .query("q", title).query("size", 100).expect(200);

            List<String> keys = new ArrayList<>();
            found.json().path("items").forEach(item -> keys.add(item.path("key").asString("")));
            assertThat(keys).as("issues the restored app finds with q=%s: %s", title, found.snippet())
                    .containsExactly(searched);
        } catch (RuntimeException e) {
            throw new AssertionError("The restore test could not go on: " + e, e);
        } finally {
            stack.close();
        }
    }

    /** Copies a {@code popdesk backup} dump of the running installation to the host. */
    private static Path backup(Stack stack) {
        stack.exec("app", null, "popdesk", "backup", DUMP_IN_APP).expectOk("popdesk backup");
        Path file = stack.work().resolve("backup.dump");
        stack.command("cp", "app:" + DUMP_IN_APP, file.toString()).expectOk("copy the dump out of app");
        file.toFile().setReadable(true, false);
        return file;
    }

    /** {@code popdesk restore} in a new container of the app service, as the role that does not own the extension. */
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
        assertThat(db.userTableCount()).as("tables in the new database").isZero();
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

    /** Who owns {@code pg_trgm} in the database; empty when it is not there. */
    private static List<String> extensionOwners(Db db) {
        return db.query("select r.rolname from pg_extension e join pg_roles r on r.oid = e.extowner "
                        + "where e.extname = 'pg_trgm'")
                .stream().map(r -> (String) r.get("rolname")).toList();
    }
}
