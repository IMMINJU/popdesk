package io.popdesk.ops;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.ops.AuditTrail.Mark;
import io.popdesk.ops.AuditTrail.Row;
import io.popdesk.support.ApiClient;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The audit row of a sign-in that fails because the account is locked (AUDIT-1). {@link AuditLogTest}
 * checks the rows of a wrong password and of the lock itself; a sign-in refused during the lock is a
 * failed sign-in too, also with the right password.
 */
class LockedSignInAuditTest extends BareAppTest {

    @Test
    @DisplayName("S0-AC-83 a sign-in refused while the account is locked leaves one more SIGN_IN_FAILED row, without the password")
    void signInWhileLockedLeavesARow() {
        AuditTrail audit = new AuditTrail(db);
        Session admin = fixtures.signInAdmin();
        Session agent = fixtures.agent(admin);
        String wrong = "wrong-" + Unique.number() + "-Password";
        ApiClient browser = console();
        for (int i = 0; i < 5; i++) {
            browser.post("/api/public/sign-in").json(Map.of("email", agent.email(), "password", wrong)).expect(401);
        }
        Mark mark = audit.mark(agent.email());

        browser.post("/api/public/sign-in").json(Map.of("email", agent.email(), "password", Fixtures.PASSWORD))
                .expect(401);

        List<Row> rows = audit.newRows(mark);
        assertThat(rows).as("new rows holding %s after a sign-in during the lock", agent.email()).hasSize(1);
        Row row = rows.getFirst();
        assertThat(row.hasText("SIGN_IN_FAILED")).as("kind SIGN_IN_FAILED in %s", row).isTrue();
        assertThat(row.person(agent.accountId(), agent.name(), agent.email()))
                .as("{accountId, name, email} of %s in %s", agent.email(), row).isPresent();
        assertThat(row.contains(Fixtures.PASSWORD)).as("password in %s", row).isFalse();
    }
}
