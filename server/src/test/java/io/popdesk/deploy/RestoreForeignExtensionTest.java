package io.popdesk.deploy;

import static io.popdesk.deploy.IssueInstallSteps.backup;
import static io.popdesk.deploy.IssueInstallSteps.emptyDatabase;
import static io.popdesk.deploy.IssueInstallSteps.restoreAs;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.deploy.AppSteps.Person;
import io.popdesk.support.ApiResponse;
import java.nio.file.Path;
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

    private static final String RESTORE_USER = "test_restore_not_extension_owner";
    private static final String RESTORE_PASSWORD = "Restore-Password-1";

    @Test
    @DisplayName("S3-AC-93 popdesk restore finishes in an empty database where a superuser created pg_trgm and the "
            + "app's database user does not own it; the database then has the extension and the gin_trgm_ops "
            + "indexes it had at the backup, and the app started on it finds an issue by its title")
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
            Db db = stack.db();
            List<String> indexes = trigramIndexes(db);
            assertThat(indexes).as("indexes with gin_trgm_ops before the backup").isNotEmpty();
            Path dump = backup(stack);
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

            Cli.Result restored = restoreAs(stack, dump, RESTORE_USER, RESTORE_PASSWORD);

            assertThat(restored.exit()).as("popdesk restore as a role that does not own pg_trgm\n%s", restored)
                    .isZero();
            assertThat(extensionOwners(db)).as("owners of pg_trgm after the restore").hasSize(1)
                    .doesNotContain(RESTORE_USER);
            assertThat(trigramIndexes(db)).as("indexes with gin_trgm_ops after the restore, as before the backup")
                    .isEqualTo(indexes);

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

    /** The names of the indexes that use the trigram operator class, in every schema, by name. */
    private static List<String> trigramIndexes(Db db) {
        return db.query("select schemaname || '.' || indexname as name from pg_indexes "
                        + "where indexdef like '%gin_trgm_ops%' order by 1")
                .stream().map(r -> (String) r.get("name")).toList();
    }

    /** Who owns {@code pg_trgm} in the database; empty when it is not there. */
    private static List<String> extensionOwners(Db db) {
        return db.query("select r.rolname from pg_extension e join pg_roles r on r.oid = e.extowner "
                        + "where e.extname = 'pg_trgm'")
                .stream().map(r -> (String) r.get("rolname")).toList();
    }
}
