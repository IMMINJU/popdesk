package io.popdesk.project;

import static io.popdesk.project.ProjectChecks.assertError;
import static io.popdesk.project.ProjectChecks.assertFieldError;
import static io.popdesk.project.ProjectChecks.pending;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Invitation;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** The admin's list of accounts that have not accepted yet. */
class PendingInvitationListTest extends BareAppTest {

    private static final String PENDING = "/api/console/invitations";

    @Test
    @DisplayName("S0-AC-40 each pending entry has email, name, role, companies, invitation time, and expiry")
    void entryFields() {
        Session admin = fixtures.signInAdmin();
        Company a = fixtures.createCompany(admin);
        Company b = fixtures.createCompany(admin);
        Instant invitedAt = clock.instant();
        Invitation customer = fixtures.invite(admin, Role.CUSTOMER, a.code(), b.code());
        Invitation agent = fixtures.invite(admin, Role.AGENT);

        JsonNode c = single(admin, customer.email());
        assertThat(c.path("accountId").asLong(-1)).isEqualTo(customer.accountId());
        assertThat(c.path("name").asString(null)).isEqualTo(customer.name());
        assertThat(c.path("role").asString(null)).isEqualTo("CUSTOMER");
        assertThat(c.path("companies").findValuesAsString("code")).containsExactlyInAnyOrder(a.code(), b.code());
        assertThat(c.path("companies").findValuesAsString("name")).containsExactlyInAnyOrder(a.name(), b.name());
        assertThat(c.path("invitedAt").asString("")).endsWith("Z");
        assertThat(Instant.parse(c.path("invitedAt").asString(""))).isEqualTo(invitedAt);
        assertThat(Instant.parse(c.path("expiresAt").asString(""))).isEqualTo(invitedAt.plus(Duration.ofDays(7)));
        assertThat(c.path("expired").isBoolean()).isTrue();
        assertThat(c.path("expired").asBoolean(true)).isFalse();

        JsonNode s = single(admin, agent.email());
        assertThat(s.path("role").asString(null)).isEqualTo("AGENT");
        assertThat(s.path("companies").isArray()).isTrue();
        assertThat(s.path("companies").size()).isZero();
    }

    @Test
    @DisplayName("S0-AC-40 an entry shows expired once its link is past 7 days")
    void showsExpired() {
        Session admin = fixtures.signInAdmin();
        Invitation invitation = fixtures.invite(admin, Role.AGENT);

        clock.advance(Duration.ofDays(7).minusMinutes(1));
        admin = fixtures.signInAdmin();
        assertThat(single(admin, invitation.email()).path("expired").asBoolean(true)).isFalse();

        clock.advance(Duration.ofMinutes(2));
        admin = fixtures.signInAdmin();
        Invitation fresh = fixtures.invite(admin, Role.AGENT);
        assertThat(single(admin, invitation.email()).path("expired").asBoolean(false)).isTrue();
        assertThat(single(admin, fresh.email()).path("expired").asBoolean(true)).isFalse();
    }

    @Test
    @DisplayName("S0-AC-40 accepted accounts leave the list")
    void acceptedLeave() {
        Session admin = fixtures.signInAdmin();
        Invitation stays = fixtures.invite(admin, Role.AGENT);
        Invitation leaves = fixtures.invite(admin, Role.AGENT);

        fixtures.accept(leaves);

        assertThat(pending(admin, stays.email())).hasSize(1);
        assertThat(pending(admin, leaves.email())).isEmpty();
    }

    @Test
    @DisplayName("S0-AC-40 the list is split into pages by page and size (COM-4)")
    void pages() {
        Session admin = fixtures.signInAdmin();
        for (int i = 0; i < 3; i++) {
            fixtures.invite(admin, Role.AGENT);
        }

        ApiResponse first = admin.client().get(PENDING).query("page", 1).query("size", 2).expect(200);
        long total = first.jsonLong("totalElements");
        assertThat(total).isGreaterThanOrEqualTo(3);
        assertThat(first.json().path("items").size()).isEqualTo(2);
        assertThat(first.jsonLong("page")).isEqualTo(1);
        assertThat(first.jsonLong("size")).isEqualTo(2);
        assertThat(first.jsonLong("totalPages")).isEqualTo((total + 1) / 2);

        ApiResponse second = admin.client().get(PENDING).query("page", 2).query("size", 2).expect(200);
        assertThat(second.jsonLong("page")).isEqualTo(2);
        assertThat(second.json().path("items").size()).isEqualTo((int) Math.min(2, total - 2));

        ApiResponse past = admin.client().get(PENDING).query("page", total + 1).query("size", 1).expect(200);
        assertThat(past.json().path("items").isArray()).isTrue();
        assertThat(past.json().path("items").size()).isZero();

        ApiResponse defaults = admin.client().get(PENDING).expect(200);
        assertThat(defaults.jsonLong("page")).isEqualTo(1);
        assertThat(defaults.jsonLong("size")).isEqualTo(20);
    }

    @Test
    @DisplayName("S0-AC-40 page below 1 or size outside 1 to 100 is 400 OUT_OF_RANGE")
    void pageLimits() {
        Session admin = fixtures.signInAdmin();

        assertFieldError(admin.client().get(PENDING).query("page", 0).send(), 400, "page", "OUT_OF_RANGE");
        assertFieldError(admin.client().get(PENDING).query("size", 0).send(), 400, "size", "OUT_OF_RANGE");
        assertFieldError(admin.client().get(PENDING).query("size", 101).send(), 400, "size", "OUT_OF_RANGE");
        assertThat(admin.client().get(PENDING).query("size", 100).expect(200).jsonLong("size")).isEqualTo(100);
    }

    @Test
    @DisplayName("S0-AC-40 an agent asking for the list gets 403 FORBIDDEN")
    void agentCannotList() {
        Session admin = fixtures.signInAdmin();
        Session agent = fixtures.agent(admin);

        assertError(agent.client().get(PENDING).send(), 403, "FORBIDDEN");
    }

    private static JsonNode single(Session admin, String email) {
        List<JsonNode> entries = pending(admin, email);
        assertThat(entries).as("pending entries for " + email).hasSize(1);
        return entries.getFirst();
    }
}
