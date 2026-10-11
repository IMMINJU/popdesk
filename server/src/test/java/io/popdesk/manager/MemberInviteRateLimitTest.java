package io.popdesk.manager;

import static io.popdesk.manager.ManagerSteps.emails;
import static io.popdesk.manager.ManagerSteps.invite;
import static io.popdesk.manager.ManagerSteps.resend;
import static io.popdesk.manager.ManagerSteps.rowId;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Invitation;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The limit on a manager's invitations and sending again: 20 of both together per account over 60
 * seconds (COM-8). The test clock stands still, so every request of a test falls in one window until
 * the test moves the clock.
 */
class MemberInviteRateLimitTest extends BareAppTest {

    private record Scene(Session admin, Company company, Session manager, String pendingRow) {}

    private Scene scene() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Session manager = ManagerSteps.manager(fixtures, admin, company.code());
        Invitation pending = fixtures.invite(admin, Role.CUSTOMER, company.code());
        return new Scene(admin, company, manager, rowId(manager, company.code(), pending.email()));
    }

    /** Ten invitations and ten times sending again, in turns: the 20 the limit allows. */
    private void useUpTheLimit(Scene s) {
        for (int i = 0; i < 10; i++) {
            invite(s.manager(), s.company().code(), Unique.email("limit"), "Limit " + i).expectStatus(204);
            s.manager().client().post(resend(s.company().code(), s.pendingRow())).expect(204);
        }
    }

    private static void assertRateLimited(ApiResponse response) {
        assertThat(response.status()).as(response.request() + ": " + response.snippet()).isEqualTo(429);
        assertThat(response.errorCode()).isEqualTo("RATE_LIMITED");
        assertThat(response.header("Retry-After")).as("Retry-After").isNotBlank();
    }

    @Test
    @DisplayName("S2-AC-20 after 20 invitations and sendings again together within 60 seconds, the next of "
            + "either is 429 with Retry-After and invites nobody")
    void twentyFirstIsLimited() {
        Scene s = scene();
        useUpTheLimit(s);
        String email = Unique.email("over");

        ApiResponse invitation = invite(s.manager(), s.company().code(), email, "Over the limit");
        ApiResponse sentAgain = s.manager().client().post(resend(s.company().code(), s.pendingRow())).send();

        assertRateLimited(invitation);
        assertRateLimited(sentAgain);
        assertThat(emails(s.manager(), s.company().code())).hasSize(12).doesNotContain(email);
    }

    @Test
    @DisplayName("S2-AC-20 the limit is per account and window: another manager of the company can still "
            + "invite, and the limited manager can after 60 seconds")
    void limitIsPerManagerAndWindow() {
        Scene s = scene();
        Session colleague = ManagerSteps.manager(fixtures, s.admin(), s.company().code());
        useUpTheLimit(s);
        assertRateLimited(invite(s.manager(), s.company().code(), Unique.email("over"), "Over the limit"));

        ApiResponse byColleague = invite(colleague, s.company().code(), Unique.email("colleague"), "By colleague");
        clock.advance(Duration.ofSeconds(61));
        ApiResponse afterWindow = invite(s.manager(), s.company().code(), Unique.email("later"), "After the window");

        assertThat(byColleague.status()).as(byColleague.request()).isEqualTo(204);
        assertThat(afterWindow.status()).as("an invitation of the limited manager after 60 seconds").isEqualTo(204);
    }
}
