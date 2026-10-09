package io.popdesk.start;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.start.Database.Row;
import io.popdesk.start.Database.Table;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.Wait;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The first start on an empty database, and later starts on a database that has an admin. */
class FirstStartTest extends OwnStartTest {

    @Test
    @DisplayName("S0-AC-01 the first start makes team 1 and one ACTIVE admin with one audit row by the system")
    void firstStartMakesTeamAndAdmin() {
        Database db = postgres.newDatabase();

        try (Boot app = Boot.start(Settings.firstInstall(db))) {
            app.assertStarted();

            Table tenant = db.table("tenant").orElseThrow(() -> new AssertionError("no tenant table in " + db.tables()));
            List<Row> teams = db.rows(tenant);
            assertThat(teams).as("rows of tenant").hasSize(1);
            assertThat(teams.getFirst().json().path("id").asLong(0)).as("team id in %s", teams).isEqualTo(1);

            assertOneAccountRow(db, Settings.ADMIN_EMAIL);
            Row account = db.accountRows().getFirst();
            assertThat(account.hasText("ACTIVE")).as("ACTIVE in %s", account).isTrue();
            assertThat(account.hasText("ADMIN")).as("role ADMIN in %s", account).isTrue();
            assertThat(account.json().path("tenant_id").asLong(0)).as("tenant_id of %s", account).isEqualTo(1);

            List<Row> audit = db.firstAdminAuditRows();
            assertThat(audit).as("FIRST_ADMIN_CREATED rows").hasSize(1);
            assertThat(audit.getFirst().hasAccountId(0)).as("person: the system, account 0, in %s", audit).isTrue();
            assertThat(audit.getFirst().contains(Settings.ADMIN_PASSWORD)).as("password in the audit row").isFalse();

            ApiResponse signIn = app.signIn(Settings.ADMIN_EMAIL, Settings.ADMIN_PASSWORD);
            assertThat(signIn.status()).as(signIn + " " + signIn.snippet()).isEqualTo(204);
        }
    }

    @Test
    @DisplayName("S0-AC-01 two apps started at once on one empty database make one admin and one audit row")
    void twoAppsAtOnceMakeOneAdmin() {
        Database db = postgres.newDatabase();
        // The second app may wait for the first one's lock and give up; only the rows count here.
        Settings settings = Settings.firstInstall(db).set("POPDESK_INSTANCE_LOCK_WAIT", "PT5S");

        Boot.Background first = Boot.startInBackground(settings);
        Boot.Background second = Boot.startInBackground(settings);
        try {
            Boot a = first.await(START);
            Boot b = second.await(START);
            assertThat(a.started() || b.started()).as("at least one app started: %s / %s", a.failure(), b.failure())
                    .isTrue();

            assertOneAccountRow(db, Settings.ADMIN_EMAIL);
            assertThat(db.firstAdminAuditRows()).as("FIRST_ADMIN_CREATED rows").hasSize(1);
        } finally {
            first.close(db);
            second.close(db);
        }
    }

    @Test
    @DisplayName("S0-AC-01 the app holds its lock while migrations run, and makes the admin only after them")
    void lockThenMigrationsThenAdmin() {
        Database db = postgres.newDatabase();
        Boot.Background start = Boot.startInBackground(Settings.firstInstall(db).withFixtureMigrations("slow"));
        try {
            Wait.until("the slow fixture migration running", () -> db.running("pg_sleep") ? true : null);

            assertThat(db.twoKeyLocks()).as("advisory locks with two int keys while migrating: %s", db.advisoryLocks())
                    .hasSize(1);
            assertThat(db.accountRows()).as("account rows while migrating").isEmpty();
            assertThat(db.table("tenant").map(db::rows).orElse(List.of())).as("teams while migrating").isEmpty();

            Boot app = start.await(START).assertStarted();
            assertThat(db.table("tenant").map(db::rows).orElse(List.of())).as("teams after start").hasSize(1);
            assertOneAccountRow(db, Settings.ADMIN_EMAIL);
            assertThat(db.twoKeyLocks()).as("the lock after start").hasSize(1);
            app.close();
        } finally {
            start.close(db);
        }
    }

    @Test
    @DisplayName("S0-AC-03 with an admin in the database, new first admin values change nothing")
    void existingAdminKeepsItsAccount() {
        Database db = postgres.newDatabase();
        try (Boot first = Boot.start(Settings.firstInstall(db))) {
            first.assertStarted();
        }
        assertOneAccountRow(db, Settings.ADMIN_EMAIL);
        String before = db.accountRows().getFirst().text();

        String otherEmail = "other-admin@start.popdesk.test";
        String otherPassword = "Other-Admin-Password-2";
        Settings changed = Settings.firstInstall(db)
                .set("POPDESK_ADMIN_EMAIL", otherEmail)
                .set("POPDESK_ADMIN_PASSWORD", otherPassword);

        try (Boot app = Boot.start(changed)) {
            app.assertStarted();

            assertThat(db.accountRows()).as("account rows after the second start").hasSize(1);
            assertThat(db.accountRows().getFirst().text()).as("the admin's row").isEqualTo(before);
            assertThat(db.rowsContaining(otherEmail)).as("rows holding %s", otherEmail).isEmpty();
            assertThat(db.firstAdminAuditRows()).as("FIRST_ADMIN_CREATED rows").hasSize(1);

            ApiResponse oldPassword = app.signIn(Settings.ADMIN_EMAIL, Settings.ADMIN_PASSWORD);
            assertThat(oldPassword.status()).as(oldPassword + " " + oldPassword.snippet()).isEqualTo(204);
            ApiResponse newValues = app.signIn(otherEmail, otherPassword);
            assertThat(newValues.status()).as(newValues.toString()).isEqualTo(401);
            assertThat(newValues.errorCode()).isEqualTo("SIGN_IN_FAILED");
            ApiResponse newPassword = app.signIn(Settings.ADMIN_EMAIL, otherPassword);
            assertThat(newPassword.status()).as(newPassword.toString()).isEqualTo(401);
        }
    }

    @Test
    @DisplayName("S0-AC-03 with an admin in the database, missing or invalid first admin values do not keep the app waiting")
    void existingAdminNeedsNoFirstAdminValues() {
        Database db = postgres.newDatabase();
        try (Boot first = Boot.start(Settings.firstInstall(db))) {
            first.assertStarted();
        }
        assertOneAccountRow(db, Settings.ADMIN_EMAIL);

        // configuration.md: after the first start the password may be removed.
        assertRunsWithTheAdmin(Settings.firstInstall(db).without("POPDESK_ADMIN_PASSWORD"), db,
                "without POPDESK_ADMIN_PASSWORD");
        assertRunsWithTheAdmin(Settings.firstInstall(db).without("POPDESK_ADMIN_EMAIL").without("POPDESK_ADMIN_PASSWORD"),
                db, "without both first admin values");
        assertRunsWithTheAdmin(Settings.firstInstall(db).set("POPDESK_ADMIN_PASSWORD", "Nine-ch1x"), db,
                "with a nine character first admin password");
    }

    /**
     * Ready, business requests answered (401, not 503 SETUP_REQUIRED), the admin's row unchanged by
     * the start, sign-in works. The row is read just before the start: a sign-in may change it.
     */
    private static void assertRunsWithTheAdmin(Settings settings, Database db, String start) {
        String before = db.accountRows().getFirst().text();
        try (Boot app = Boot.start(settings)) {
            app.assertStarted();
            assertReady(app);

            ApiResponse me = app.console().get("/api/console/me").send();
            assertThat(me.status()).as("%s: %s %s", start, me, me.snippet()).isEqualTo(401);
            assertThat(me.errorCode()).as("%s: %s", start, me).isNotEqualTo("SETUP_REQUIRED");

            assertThat(db.accountRows()).as("%s: account rows", start).hasSize(1);
            assertThat(db.accountRows().getFirst().text()).as("%s: the admin's row", start).isEqualTo(before);
            ApiResponse signIn = app.signIn(Settings.ADMIN_EMAIL, Settings.ADMIN_PASSWORD);
            assertThat(signIn.status()).as("%s: %s %s", start, signIn, signIn.snippet()).isEqualTo(204);
        }
    }
}
