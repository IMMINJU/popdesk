package io.popdesk.start;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An outside database whose user may create tables but not the {@code pg_trgm} extension the issue
 * search needs (ADR D-13, slice 3 premortem section 6): the user is neither a superuser nor the
 * owner of the database and has no {@code CREATE} privilege on it, as on a managed PostgreSQL where
 * the administrator hands out a schema. Without the extension the app must not start and must tell
 * the administrator what to run; once the administrator created the extension, the same user starts
 * the app.
 */
class TrigramExtensionTest extends OwnStartTest {

    /** The statement a database administrator runs, with or without IF NOT EXISTS and quotes. */
    private static final Pattern CREATE_EXTENSION = Pattern.compile(
            "CREATE\\s+EXTENSION\\s+(IF\\s+NOT\\s+EXISTS\\s+)?\"?pg_trgm\"?", Pattern.CASE_INSENSITIVE);

    @Test
    @DisplayName("S3-AC-91 a database user that may not create the pg_trgm extension, on a database without it, "
            + "stops the start and the log has the CREATE EXTENSION statement for the database administrator")
    void userThatMayNotCreateTheExtension() {
        Database db = newDatabaseWithoutExtensionPrivilege();

        try (Boot app = Boot.start(Settings.firstInstall(db))) {
            app.assertDidNotStart("user " + db.user() + " may not create the extension pg_trgm, and it is not there");

            assertThat(app.logLines()).as("a line with the statement that creates pg_trgm")
                    .anyMatch(line -> CREATE_EXTENSION.matcher(line).find());
            assertThat(extensionOwners(db)).as("owners of pg_trgm after the refused start").isEmpty();
        }
    }

    @Test
    @DisplayName("S3-AC-91 once an administrator created pg_trgm, the same database user starts the app and its "
            + "migrations build a trigram index")
    void extensionCreatedByAnAdministrator() {
        Database db = newDatabaseWithoutExtensionPrivilege();
        db.execute("create extension pg_trgm");

        try (Boot app = Boot.start(Settings.firstInstall(db))) {
            app.assertStarted();
            assertReady(app);

            assertThat(extensionOwners(db)).as("owners of pg_trgm").hasSize(1).doesNotContain(db.user());
            assertThat(db.query("select indexname from pg_indexes where indexdef like '%gin_trgm_ops%'",
                    rs -> rs.getString(1))).as("indexes with gin_trgm_ops").isNotEmpty();
        }
    }

    /**
     * A new empty database that belongs to the superuser, where the app's role may create tables in
     * {@code public} but has no {@code CREATE} on the database: every migration step works but
     * creating an extension. Fails unless the privileges are exactly that.
     */
    private static Database newDatabaseWithoutExtensionPrivilege() {
        Database db = postgres.newDatabaseWithoutCreate();
        db.execute("grant create on schema public to " + db.user());
        List<String> privileges = db.query("select r.rolsuper::text || ' ' "
                + "|| has_database_privilege(r.rolname, current_database(), 'CREATE')::text || ' ' "
                + "|| has_schema_privilege(r.rolname, 'public', 'CREATE')::text || ' ' "
                + "|| (select count(*) from pg_database d where d.datname = current_database() "
                + "and d.datdba = r.oid)::text from pg_roles r where r.rolname = '" + db.user() + "'",
                rs -> rs.getString(1));
        assertThat(privileges).as("superuser, CREATE on the database, CREATE on public, databases owned: %s",
                db.user()).containsExactly("false false true 0");
        return db;
    }

    /** Who owns {@code pg_trgm} in this database; empty when it is not there. */
    private static List<String> extensionOwners(Database db) {
        return db.query("select r.rolname from pg_extension e join pg_roles r on r.oid = e.extowner "
                + "where e.extname = 'pg_trgm'", rs -> rs.getString(1));
    }
}
