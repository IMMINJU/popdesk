package io.popdesk.ops;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiResponse;
import io.popdesk.support.AppLog.Mark;
import io.popdesk.support.AppTest;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The DETAIL of a database error never reaches the log, also when the database URL does not carry
 * {@code logServerErrorDetail=false} (verify.md V-14, S0 premortem 4). PostgreSQL puts row values
 * there ({@code Key (code)=(...) already exists}, the failing row of a constraint), and they are not
 * all emails that the log's masking would catch.
 *
 * <p>The test contexts get their database from Testcontainers, whose URL has no
 * {@code logServerErrorDetail}: the case of an operator who changed {@code POPDESK_DB_URL} and left
 * the flag out. The values looked for are not emails, so only the missing DETAIL keeps them out.
 */
class DatabaseErrorDetailLogTest extends BareAppTest {

    @Test
    @DisplayName("V-14 S0-AC-86 a 500 from a database error whose DETAIL holds the row leaves no value of the row in the log")
    void detailOfUnexpectedError() {
        Session admin = fixtures.signInAdmin();
        String email = Unique.email("detail");
        String name = Unique.text("Detail value") + " " + Unique.number();
        String table = accountTable();
        String function = quote(db.schema()) + ".test_detail_" + Unique.number();
        String trigger = "test_detail_" + Unique.number();
        db.schemaFixture("create function " + function + "() returns trigger language plpgsql as $$ begin "
                + "if row_to_json(NEW)::text like '%" + email + "%' then raise exception using errcode = 'PD500', "
                + "message = 'test failure', detail = row_to_json(NEW)::text; "
                + "end if; return NEW; end $$");
        try {
            db.schemaFixture("create trigger " + trigger + " before insert on " + table + " for each row "
                    + "execute function " + function + "()");
            Mark mark = log.mark();

            ApiResponse response = admin.client().post("/api/console/invitations")
                    .json(Map.of("email", email, "name", name, "role", "AGENT")).send();

            assertThat(response.status()).as(response.snippet()).isEqualTo(500);
            assertThat(response.errorCode()).isEqualTo("INTERNAL_ERROR");
            assertThat(LogForms.foundIn(response.text(), List.of(name))).as("the name in the response").isEmpty();
            log.awaitLine(mark, "PD500");
            assertThat(LogForms.foundIn(log.since(mark), List.of(name))).as("the name, from the error's DETAIL, in "
                    + "the log").isEmpty();
        } finally {
            db.schemaFixture("drop trigger if exists " + trigger + " on " + table);
            db.schemaFixture("drop function if exists " + function + "()");
        }
    }

    @Test
    @DisplayName("V-14 S0-AC-86 a 409 from a unique violation leaves no DETAIL (Key (...)=(value)) in the log")
    void detailOfUniqueViolation() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Mark mark = log.mark();

        admin.client().post("/api/console/companies")
                .json(Map.of("code", company.code(), "name", "Again", "legalName", "Again Ltd."))
                .expect(409);

        assertThat(log.since(mark)).as("the log since the 409").doesNotContain("=(" + company.code() + ")")
                .doesNotContain("already exists");
    }

    /** {@code "schema"."table"} of the account table: the one table with a text column equal to the first admin's email. */
    private String accountTable() {
        List<String> found = new ArrayList<>();
        for (Map<String, Object> col : db.rows("select table_name, column_name from information_schema.columns "
                + "where table_schema = ? and (data_type in ('text', 'character varying') or udt_name = 'citext')",
                db.schema())) {
            String table = quote(db.schema()) + "." + quote((String) col.get("table_name"));
            if (db.count("select count(*) from " + table + " where " + quote((String) col.get("column_name"))
                    + "::text = ?", AppTest.ADMIN_EMAIL) > 0) {
                found.add(table);
            }
        }
        assertThat(found).as("tables with a text column equal to %s", AppTest.ADMIN_EMAIL).hasSize(1);
        return found.getFirst();
    }

    private static String quote(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }
}
