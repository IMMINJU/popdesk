package io.popdesk.manager;

import static io.popdesk.manager.ManagerSteps.addCustomer;
import static io.popdesk.manager.ManagerSteps.invited;
import static io.popdesk.manager.ManagerSteps.mark;
import static io.popdesk.manager.ManagerSteps.pages;
import static io.popdesk.manager.ManagerSteps.pendingInvitations;
import static io.popdesk.manager.ManagerSteps.remove;
import static io.popdesk.manager.ManagerSteps.resendTo;
import static io.popdesk.manager.ManagerSteps.unmark;
import static io.popdesk.manager.RequestSteps.approve;
import static io.popdesk.manager.RequestSteps.reject;
import static io.popdesk.manager.RequestSteps.request;
import static io.popdesk.manager.RequestSteps.withdraw;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.manager.ManagerSteps.Scene;
import io.popdesk.support.AuditChainMath;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Invitation;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
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
 *
 * <p>The target and the changes of an entry go into the hash chain, so they are compared whole. The
 * new kinds have the shapes of the table under section 0 of the slice's design.md. The kinds that were
 * there before
 * ({@code INVITATION_CREATED}, {@code INVITATION_RESENT}, {@code INVITATION_CANCELLED},
 * {@code CUSTOMER_REMOVED}, {@code CUSTOMER_ADDED}) keep the shape an admin's action leaves; only the
 * person who did it is the manager.
 */
class ManagerAuditTest extends BareAppTest {

    private static final String LOG = "/api/console/audit-log";

    /** How an audit entry writes a time in its changes: microseconds, UTC. */
    private static final DateTimeFormatter CHANGE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'")
            .withZone(ZoneOffset.UTC);

    private Scene scene() {
        return ManagerSteps.scene(fixtures, fixtures.admin(fixtures.signInAdmin()));
    }

    /** One entry an action must leave: its kind, and its whole target and changes. */
    private record Entry(String kind, JsonNode target, JsonNode changes) {}

    /**
     * An action about to be done: who does it, the action (it returns the entries it must leave, all of
     * them), and values no entry may hold.
     */
    private record Step(Session actor, Supplier<List<Entry>> action, List<String> secrets) {}

    private static JsonNode companyTarget(String code, long accountId) {
        return AuditChainMath.parse("{\"type\":\"COMPANY\",\"code\":\"" + code + "\",\"accountId\":" + accountId + "}");
    }

    private static JsonNode accountTarget(long accountId) {
        return AuditChainMath.parse("{\"type\":\"ACCOUNT\",\"accountId\":" + accountId + "}");
    }

    private static JsonNode json(String text) {
        return AuditChainMath.parse(text);
    }

    /** What is done in this slice that the audit log records, by the kinds of entry it leaves. */
    private enum Action {
        MARKING(false, "MANAGER_MARKED"),
        UNMARKING(false, "MANAGER_UNMARKED"),
        INVITING_A_NEW_EMAIL(true, "INVITATION_CREATED"),
        INVITING_AN_EXISTING_EMAIL(true, "MEMBERSHIP_REQUESTED"),
        SENDING_AGAIN(true, "INVITATION_RESENT"),
        REMOVING_AN_ACTIVE_MEMBER(true, "CUSTOMER_REMOVED"),
        REMOVING_A_PENDING_MEMBER_OF_THIS_COMPANY_ONLY(true, "CUSTOMER_REMOVED", "INVITATION_CANCELLED"),
        WITHDRAWING_A_REQUEST(true, "MEMBERSHIP_REQUEST_WITHDRAWN"),
        APPROVING_A_REQUEST(false, "MEMBERSHIP_REQUEST_APPROVED", "CUSTOMER_ADDED"),
        ADDING_THE_CUSTOMER_OF_A_REQUEST(false, "MEMBERSHIP_REQUEST_APPROVED", "CUSTOMER_ADDED"),
        REJECTING_A_REQUEST(false, "MEMBERSHIP_REQUEST_REJECTED");

        private final boolean byManager;
        private final List<String> kinds;

        Action(boolean byManager, String... kinds) {
            this.byManager = byManager;
            this.kinds = List.of(kinds);
        }
    }

    /** Prepares the action; nothing of its kinds is written before {@code action} runs. */
    private Step prepare(Action action, Scene s) {
        String code = s.code();
        switch (action) {
            case MARKING -> {
                Session customer = fixtures.customer(s.admin(), code);
                return new Step(s.admin(), () -> {
                    mark(s.admin(), code, customer.accountId());
                    return List.of(new Entry("MANAGER_MARKED", companyTarget(code, customer.accountId()),
                            json("{\"manager\":true}")));
                }, List.of());
            }
            case UNMARKING -> {
                Session customer = fixtures.customer(s.admin(), code);
                mark(s.admin(), code, customer.accountId());
                return new Step(s.admin(), () -> {
                    unmark(s.admin(), code, customer.accountId());
                    return List.of(new Entry("MANAGER_UNMARKED", companyTarget(code, customer.accountId()),
                            json("{\"manager\":false}")));
                }, List.of());
            }
            case INVITING_A_NEW_EMAIL -> {
                String email = Unique.email("invited");
                String name = Unique.text("Invited");
                return new Step(s.manager(), () -> {
                    invited(s.manager(), code, email, name);
                    List<JsonNode> pending = pendingInvitations(s.admin(), email);
                    assertThat(pending).as("pending invitations of %s", email).hasSize(1);
                    return List.of(new Entry("INVITATION_CREATED",
                            accountTarget(pending.getFirst().path("accountId").asLong(-1)),
                            json("{\"email\":\"" + email + "\",\"name\":\"" + name + "\",\"role\":\"CUSTOMER\","
                                    + "\"companyCodes\":[\"" + code + "\"]}")));
                }, List.of());
            }
            case INVITING_AN_EXISTING_EMAIL -> {
                Session target = fixtures.customer(s.admin(), s.other().code());
                String name = Unique.text("Typed");
                return new Step(s.manager(), () -> {
                    invited(s.manager(), code, target.email(), name);
                    return List.of(new Entry("MEMBERSHIP_REQUESTED", companyTarget(code, target.accountId()),
                            json("{\"email\":\"" + target.email() + "\",\"name\":\"" + name + "\"}")));
                }, List.of());
            }
            case SENDING_AGAIN -> {
                Invitation invitation = fixtures.invite(s.admin(), Role.CUSTOMER, code);
                return new Step(s.manager(), () -> {
                    resendTo(s.manager(), code, invitation.email()).expectStatus(204);
                    List<JsonNode> pending = pendingInvitations(s.admin(), invitation.email());
                    assertThat(pending).as("pending invitations of %s", invitation.email()).hasSize(1);
                    String expiresAt = CHANGE_TIME.format(OffsetDateTime.parse(
                            pending.getFirst().path("expiresAt").asString("")));
                    return List.of(new Entry("INVITATION_RESENT", accountTarget(invitation.accountId()),
                            json("{\"expiresAt\":\"" + expiresAt + "\"}")));
                }, List.of(invitation.token()));
            }
            case REMOVING_AN_ACTIVE_MEMBER -> {
                Session customer = fixtures.customer(s.admin(), code);
                return new Step(s.manager(), () -> {
                    remove(s.manager(), code, customer.email()).expectStatus(204);
                    return List.of(new Entry("CUSTOMER_REMOVED", companyTarget(code, customer.accountId()),
                            json("{\"member\":false}")));
                }, List.of());
            }
            case REMOVING_A_PENDING_MEMBER_OF_THIS_COMPANY_ONLY -> {
                Invitation invitation = fixtures.invite(s.admin(), Role.CUSTOMER, code);
                return new Step(s.manager(), () -> {
                    remove(s.manager(), code, invitation.email()).expectStatus(204);
                    return List.of(
                            new Entry("CUSTOMER_REMOVED", companyTarget(code, invitation.accountId()),
                                    json("{\"member\":false}")),
                            new Entry("INVITATION_CANCELLED", accountTarget(invitation.accountId()),
                                    json("{\"status\":{\"from\":\"INVITED\",\"to\":\"SUSPENDED\"}}")));
                }, List.of(invitation.token()));
            }
            case WITHDRAWING_A_REQUEST -> {
                Session target = fixtures.customer(s.admin(), s.other().code());
                request(s.admin(), s.manager(), code, target.email(), "Typed");
                return new Step(s.manager(), () -> {
                    withdraw(s.manager(), code, target.email());
                    return List.of(new Entry("MEMBERSHIP_REQUEST_WITHDRAWN", companyTarget(code, target.accountId()),
                            json("{\"email\":\"" + target.email() + "\"}")));
                }, List.of());
            }
            case APPROVING_A_REQUEST, ADDING_THE_CUSTOMER_OF_A_REQUEST -> {
                Session target = fixtures.customer(s.admin(), s.other().code());
                long id = request(s.admin(), s.manager(), code, target.email(), "Typed");
                return new Step(s.admin(), () -> {
                    if (action == Action.APPROVING_A_REQUEST) {
                        approve(s.admin(), id).expectStatus(200);
                    } else {
                        addCustomer(s.admin(), code, target.accountId());
                    }
                    return List.of(
                            new Entry("MEMBERSHIP_REQUEST_APPROVED", companyTarget(code, target.accountId()),
                                    json("{\"email\":\"" + target.email() + "\"}")),
                            new Entry("CUSTOMER_ADDED", companyTarget(code, target.accountId()),
                                    json("{\"member\":true}")));
                }, List.of());
            }
            default -> {
                Session target = fixtures.customer(s.admin(), s.other().code());
                long id = request(s.admin(), s.manager(), code, target.email(), "Typed");
                return new Step(s.admin(), () -> {
                    reject(s.admin(), id).expectStatus(200);
                    return List.of(new Entry("MEMBERSHIP_REQUEST_REJECTED", companyTarget(code, target.accountId()),
                            json("{\"email\":\"" + target.email() + "\"}")));
                }, List.of());
            }
        }
    }

    /** The entries of one person, newest first. */
    private static List<JsonNode> entriesBy(Session reader, Session actor) {
        return pages(reader.client(), LOG, "actorAccountId", actor.accountId());
    }

    /** The entries of {@code after} that are not in {@code before}, by ID. */
    private static List<JsonNode> added(List<JsonNode> before, List<JsonNode> after) {
        List<Long> known = before.stream().map(e -> e.path("id").asLong(-1)).toList();
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode entry : after) {
            if (!known.contains(entry.path("id").asLong(-1))) {
                out.add(entry);
            }
        }
        return out;
    }

    // S2-AC-41

    @ParameterizedTest(name = "{0}")
    @EnumSource(Action.class)
    @DisplayName("S2-AC-41 marking and unmarking a manager, a manager's invitation of a new and of an existing "
            + "email, sending again, removal (with the invitation it cancels), and a membership request "
            + "withdrawn, approved (also by adding the customer on the console), and rejected leave exactly "
            + "their audit entries by the person who did it, with the whole target and changes; no entry holds "
            + "a token or a link")
    void eachActionLeavesItsEntries(Action action) {
        Scene s = scene();
        Step step = prepare(action, s);
        assertThat(step.actor().accountId() == s.manager().accountId()).as("the manager does it")
                .isEqualTo(action.byManager);
        List<JsonNode> before = entriesBy(s.admin(), step.actor());

        List<Entry> expected = step.action().get();

        List<JsonNode> all = entriesBy(s.admin(), step.actor());
        List<JsonNode> added = added(before, all);
        assertThat(added).extracting(e -> e.path("action").asString(null))
                .as("kinds of the entries by %s after %s", step.actor().email(), action)
                .containsExactlyInAnyOrderElementsOf(action.kinds);
        assertThat(expected).extracting(Entry::kind).containsExactlyInAnyOrderElementsOf(action.kinds);
        for (Entry wanted : expected) {
            JsonNode entry = added.stream().filter(e -> wanted.kind().equals(e.path("action").asString(null)))
                    .findFirst().orElseThrow();
            assertThat(entry.path("actor").path("accountId").asLong(-1)).as("who did it in %s", entry)
                    .isEqualTo(step.actor().accountId());
            assertThat(entry.path("actor").path("email").asString(null)).isEqualTo(step.actor().email());
            assertThat(AuditChainMath.canonical(entry.path("target"))).as("target of the %s entry", wanted.kind())
                    .isEqualTo(AuditChainMath.canonical(wanted.target()));
            assertThat(AuditChainMath.canonical(entry.path("changes"))).as("changes of the %s entry", wanted.kind())
                    .isEqualTo(AuditChainMath.canonical(wanted.changes()));
        }
        for (JsonNode any : all) {
            assertThat(any.toString()).as("an entry by %s", step.actor().email())
                    .doesNotContain("token=").doesNotContain("/invite");
            for (String secret : step.secrets()) {
                assertThat(any.toString()).as("an entry by %s", step.actor().email()).doesNotContain(secret);
            }
        }
    }

    // S2-AC-42

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"sending again on a request row", "marking again", "unmarking again",
            "approving again", "rejecting again"})
    @DisplayName("S2-AC-42 a request that changes nothing leaves no audit entry: sending again on the row of a "
            + "membership request, and marking, unmarking, approving, or rejecting a second time")
    void nothingDoneLeavesNoEntry(String what) {
        Scene s = scene();
        Session target = fixtures.customer(s.admin(), s.other().code());
        Session customer = fixtures.customer(s.admin(), s.code());
        long requestId = request(s.admin(), s.manager(), s.code(), target.email(), "Typed");
        Session actor = what.startsWith("sending") ? s.manager() : s.admin();
        Runnable action = switch (what) {
            case "sending again on a request row" -> () -> resendTo(s.manager(), s.code(), target.email())
                    .expectStatus(204);
            case "marking again" -> () -> mark(s.admin(), s.code(), customer.accountId());
            case "unmarking again" -> {
                mark(s.admin(), s.code(), customer.accountId());
                yield () -> unmark(s.admin(), s.code(), customer.accountId());
            }
            case "approving again" -> () -> approve(s.admin(), requestId).expectStatus(200);
            default -> () -> reject(s.admin(), requestId).expectStatus(200);
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
        for (Action action : Action.values()) {
            Step step = prepare(action, s);
            List<JsonNode> before = entriesBy(s.admin(), step.actor());
            step.action().get();
            assertThat(added(before, entriesBy(s.admin(), step.actor())))
                    .extracting(e -> e.path("action").asString(null))
                    .as("kinds of the entries by %s after %s", step.actor().email(), action)
                    .containsAll(action.kinds);
        }

        AuditChainMath.assertValid(s.admin().client());
    }
}
