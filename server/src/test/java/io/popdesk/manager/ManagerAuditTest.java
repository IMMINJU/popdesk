package io.popdesk.manager;

import static io.popdesk.manager.ManagerSteps.invited;
import static io.popdesk.manager.ManagerSteps.mark;
import static io.popdesk.manager.ManagerSteps.member;
import static io.popdesk.manager.ManagerSteps.pages;
import static io.popdesk.manager.ManagerSteps.resend;
import static io.popdesk.manager.ManagerSteps.rowId;
import static io.popdesk.manager.ManagerSteps.unmark;
import static io.popdesk.manager.RequestSteps.approve;
import static io.popdesk.manager.RequestSteps.reject;
import static io.popdesk.manager.RequestSteps.request;
import static io.popdesk.manager.RequestSteps.withdraw;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.AuditChainMath;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Invitation;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/**
 * The audit log of slice 2: marking managers, what a manager does to the member list, and membership
 * requests. Every test acts as an admin and a manager of its own and narrows the log to one of them,
 * because every test of the context writes to the same log.
 */
class ManagerAuditTest extends BareAppTest {

    private static final String LOG = "/api/console/audit-log";

    private record Scene(Session admin, Company company, Company other, Session manager) {

        String code() {
            return company.code();
        }
    }

    private Scene scene() {
        Session admin = fixtures.admin(fixtures.signInAdmin());
        Company company = fixtures.createCompany(admin);
        Company other = fixtures.createCompany(admin);
        return new Scene(admin, company, other, ManagerSteps.manager(fixtures, admin, company.code()));
    }

    /** What was done: who did it, and what its entry must and must not hold. */
    private record Done(Session actor, List<String> mentioned, List<String> secrets) {}

    /** The kinds of audit entry this slice writes, each with the one action that causes it. */
    private enum Kind {
        MANAGER_MARKED(false),
        MANAGER_UNMARKED(false),
        INVITATION_CREATED(true),
        INVITATION_RESENT(true),
        CUSTOMER_REMOVED(true),
        INVITATION_CANCELLED(true),
        MEMBERSHIP_REQUESTED(true),
        MEMBERSHIP_REQUEST_WITHDRAWN(true),
        MEMBERSHIP_REQUEST_APPROVED(false),
        MEMBERSHIP_REQUEST_REJECTED(false);

        private final boolean byManager;

        Kind(boolean byManager) {
            this.byManager = byManager;
        }
    }

    /** Does the one thing that writes an entry of this kind, as a person who did nothing of that kind before. */
    private Done cause(Kind kind, Scene s) {
        switch (kind) {
            case MANAGER_MARKED -> {
                Session customer = fixtures.customer(s.admin(), s.code());
                Session admin = fixtures.admin(s.admin());
                mark(admin, s.code(), customer.accountId());
                return new Done(admin, List.of(s.code()), List.of());
            }
            case MANAGER_UNMARKED -> {
                Session customer = fixtures.customer(s.admin(), s.code());
                mark(s.admin(), s.code(), customer.accountId());
                unmark(s.admin(), s.code(), customer.accountId());
                return new Done(s.admin(), List.of(s.code()), List.of());
            }
            case INVITATION_CREATED -> {
                String email = Unique.email("invited");
                invited(s.manager(), s.code(), email, "Invited");
                return new Done(s.manager(), List.of(), List.of());
            }
            case INVITATION_RESENT -> {
                Invitation invitation = fixtures.invite(s.admin(), Role.CUSTOMER, s.code());
                s.manager().client().post(resend(s.code(), rowId(s.manager(), s.code(), invitation.email())))
                        .expect(204);
                return new Done(s.manager(), List.of(), List.of(invitation.token()));
            }
            case CUSTOMER_REMOVED -> {
                Session customer = fixtures.customer(s.admin(), s.code());
                s.manager().client().delete(member(s.code(), rowId(s.manager(), s.code(), customer.email())))
                        .expect(204);
                return new Done(s.manager(), List.of(), List.of());
            }
            case INVITATION_CANCELLED -> {
                Invitation invitation = fixtures.invite(s.admin(), Role.CUSTOMER, s.code());
                s.manager().client().delete(member(s.code(), rowId(s.manager(), s.code(), invitation.email())))
                        .expect(204);
                return new Done(s.manager(), List.of(), List.of(invitation.token()));
            }
            case MEMBERSHIP_REQUESTED -> {
                Session target = fixtures.customer(s.admin(), s.other().code());
                request(s.admin(), s.manager(), s.code(), target.email(), "Typed");
                return new Done(s.manager(), List.of(s.code(), target.email()), List.of());
            }
            case MEMBERSHIP_REQUEST_WITHDRAWN -> {
                Session target = fixtures.customer(s.admin(), s.other().code());
                request(s.admin(), s.manager(), s.code(), target.email(), "Typed");
                withdraw(s.manager(), s.code(), target.email());
                return new Done(s.manager(), List.of(s.code(), target.email()), List.of());
            }
            case MEMBERSHIP_REQUEST_APPROVED -> {
                Session target = fixtures.customer(s.admin(), s.other().code());
                long id = request(s.admin(), s.manager(), s.code(), target.email(), "Typed");
                approve(s.admin(), id).expectStatus(200);
                return new Done(s.admin(), List.of(s.code(), target.email()), List.of());
            }
            default -> {
                Session target = fixtures.customer(s.admin(), s.other().code());
                long id = request(s.admin(), s.manager(), s.code(), target.email(), "Typed");
                reject(s.admin(), id).expectStatus(200);
                return new Done(s.admin(), List.of(s.code(), target.email()), List.of());
            }
        }
    }

    /** The entries of one person, newest first; {@code kinds} narrows them to those kinds. */
    private static List<JsonNode> entriesBy(Session reader, Session actor, Kind... kinds) {
        List<Object> query = new ArrayList<>(List.of("actorAccountId", actor.accountId()));
        for (Kind kind : kinds) {
            query.add("action");
            query.add(kind.name());
        }
        return pages(reader.client(), LOG, query.toArray());
    }

    // S2-AC-41

    @ParameterizedTest(name = "{0}")
    @EnumSource(Kind.class)
    @DisplayName("S2-AC-41 marking and unmarking a manager, a manager's invitation, sending again, and removal "
            + "(with the invitation it cancels), and a membership request made, withdrawn, approved, and "
            + "rejected each leave one audit entry by the person who did it; a request's entry holds the company "
            + "code and the typed email; no entry holds a token or a link")
    void eachActionLeavesAnEntry(Kind kind) {
        Scene s = scene();

        Done done = cause(kind, s);

        assertThat(done.actor().accountId() == s.manager().accountId()).as("the manager did it")
                .isEqualTo(kind.byManager);
        List<JsonNode> found = entriesBy(s.admin(), done.actor(), kind);
        assertThat(found).as("%s entries by %s", kind, done.actor().email()).hasSize(1);
        JsonNode entry = found.getFirst();
        assertThat(entry.path("action").asString(null)).isEqualTo(kind.name());
        assertThat(entry.path("actor").path("accountId").asLong(-1)).as("who did it in %s", entry)
                .isEqualTo(done.actor().accountId());
        assertThat(entry.path("actor").path("email").asString(null)).isEqualTo(done.actor().email());
        String about = entry.path("target").toString() + entry.path("changes").toString();
        for (String value : done.mentioned()) {
            assertThat(about).as("target and changes of the %s entry", kind).contains(value);
        }
        for (JsonNode any : entriesBy(s.admin(), done.actor())) {
            assertThat(any.toString()).as("an entry by %s", done.actor().email())
                    .doesNotContain("token=").doesNotContain("/invite");
            for (String secret : done.secrets()) {
                assertThat(any.toString()).as("an entry by %s", done.actor().email()).doesNotContain(secret);
            }
        }
    }

    // S2-AC-42

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"sending again on a request row", "marking again", "unmarking again",
            "approving again"})
    @DisplayName("S2-AC-42 a request that changes nothing leaves no audit entry: sending again on the row of a "
            + "membership request, and marking, unmarking, or approving a second time")
    void nothingDoneLeavesNoEntry(String what) {
        Scene s = scene();
        Session target = fixtures.customer(s.admin(), s.other().code());
        Session customer = fixtures.customer(s.admin(), s.code());
        long requestId = request(s.admin(), s.manager(), s.code(), target.email(), "Typed");
        Session actor = what.startsWith("sending") ? s.manager() : s.admin();
        Runnable action = switch (what) {
            case "sending again on a request row" -> () -> s.manager().client()
                    .post(resend(s.code(), rowId(s.manager(), s.code(), target.email()))).expect(204);
            case "marking again" -> () -> mark(s.admin(), s.code(), customer.accountId());
            case "unmarking again" -> {
                mark(s.admin(), s.code(), customer.accountId());
                yield () -> unmark(s.admin(), s.code(), customer.accountId());
            }
            default -> () -> approve(s.admin(), requestId).expectStatus(200);
        };
        if (!what.startsWith("sending")) {
            action.run();
        }
        List<JsonNode> before = entriesBy(s.admin(), actor);
        assertThat(before).as("entries by %s before", actor.email()).isNotEmpty();

        action.run();

        assertThat(entriesBy(s.admin(), actor)).as("entries by %s after %s", actor.email(), what).isEqualTo(before);
    }

    // S2-AC-43

    @Test
    @DisplayName("S2-AC-43 after one entry of every kind of this slice joined the chain, the integrity check "
            + "says every entry holds")
    void chainHoldsWithTheNewKinds() {
        Scene s = scene();
        for (Kind kind : Kind.values()) {
            Done done = cause(kind, s);
            assertThat(entriesBy(s.admin(), done.actor(), kind)).as("%s entries by %s", kind, done.actor().email())
                    .isNotEmpty();
        }

        AuditChainMath.assertValid(s.admin().client());
    }
}
