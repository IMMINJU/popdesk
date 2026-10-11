package io.popdesk.manager;

import static io.popdesk.manager.ManagerSteps.assertError;
import static io.popdesk.manager.ManagerSteps.awaitALaterMail;
import static io.popdesk.manager.ManagerSteps.pages;
import static io.popdesk.manager.RequestSteps.approve;
import static io.popdesk.manager.RequestSteps.reject;
import static io.popdesk.manager.RequestSteps.request;
import static io.popdesk.manager.RequestSteps.withdraw;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.manager.ManagerSteps.Scene;
import io.popdesk.support.ConnectedAppTest;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Wait;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/**
 * Who hears of a membership request, with SMTP: every active admin gets an item in the console inbox
 * and no mail, and the account the request is about gets nothing at any step.
 *
 * <p>"No mail" is checked after a later mail arrived, as in {@link MemberMailTest}.
 */
class MembershipRequestMailTest extends ConnectedAppTest {

    private static final String INBOX = "/api/console/notifications";

    private Scene scene() {
        return ManagerSteps.scene(fixtures);
    }

    /** The MEMBERSHIP_REQUESTED items about this company in a person's inbox. */
    private static List<JsonNode> itemsAbout(Session staff, String code) {
        return pages(staff.client(), INBOX).stream()
                .filter(n -> "MEMBERSHIP_REQUESTED".equals(n.path("kind").asString(null)))
                .filter(n -> code.equals(n.path("companyCode").asString(null)))
                .toList();
    }

    // S2-AC-39

    @Test
    @DisplayName("S2-AC-39 a new membership request puts one MEMBERSHIP_REQUESTED item with the company's code "
            + "in the inbox of every ACTIVE admin, none in a suspended admin's or an agent's, and sends them no "
            + "mail")
    void requestIsAnInboxItemForActiveAdmins() {
        Scene s = scene();
        Session second = fixtures.admin(s.admin());
        Session suspended = fixtures.admin(s.admin());
        ManagerSteps.suspend(s.admin(), suspended.accountId());
        Session agent = fixtures.agent(s.admin(), s.code());
        Session target = fixtures.customer(s.admin(), s.other().code());
        List<String> staff = List.of(s.admin().email(), second.email(), suspended.email(), agent.email());
        List<Integer> mailBefore = staff.stream().map(e -> mail().messagesTo(e).size()).toList();

        request(s.admin(), s.manager(), s.code(), target.email(), "Typed");

        for (Session admin : List.of(s.admin(), second)) {
            List<JsonNode> items = Wait.until("an item about " + s.code() + " in the inbox of " + admin.email(),
                    () -> {
                        List<JsonNode> found = itemsAbout(admin, s.code());
                        return found.isEmpty() ? null : found;
                    });
            assertThat(items).as("items about %s in the inbox of %s", s.code(), admin.email()).hasSize(1);
            assertThat(items.getFirst().path("readAt").isNull()).as("readAt of %s", items.getFirst()).isTrue();
        }
        assertThat(itemsAbout(agent, s.code())).as("items about %s in the inbox of an agent", s.code()).isEmpty();
        s.admin().client().post("/api/console/accounts/" + suspended.accountId() + "/reactivate").expect(200);
        assertThat(itemsAbout(fixtures.signInAgain(suspended), s.code()))
                .as("items for the admin who was suspended when the request was made").isEmpty();
        awaitALaterMail(s, mail());
        assertThat(staff.stream().map(e -> mail().messagesTo(e).size()).toList()).as("mail to %s", staff)
                .isEqualTo(mailBefore);
    }

    // S2-AC-40

    @ParameterizedTest(name = "the account of {0}")
    @ValueSource(strings = {"a customer", "an agent"})
    @DisplayName("S2-AC-40 the account a request is about gets no mail, and an agent no inbox item, when the "
            + "request is made, withdrawn, rejected, or approved (for an agent's account approving is refused)")
    void targetAccountHearsNothing(String whose) {
        Scene s = scene();
        boolean agent = whose.equals("an agent");
        Session target = agent ? fixtures.agent(s.admin()) : fixtures.customer(s.admin(), s.other().code());
        int mailBefore = mail().messagesTo(target.email()).size();
        List<JsonNode> inboxBefore = agent ? pages(target.client(), INBOX) : List.of();

        request(s.admin(), s.manager(), s.code(), target.email(), "Typed");
        withdraw(s.manager(), s.code(), target.email());
        long rejected = request(s.admin(), s.manager(), s.code(), target.email(), "Typed");
        reject(s.admin(), rejected).expectStatus(200);
        long last = request(s.admin(), s.manager(), s.code(), target.email(), "Typed");
        if (agent) {
            assertError(approve(s.admin(), last), 400, "NOT_A_CUSTOMER");
        } else {
            approve(s.admin(), last).expectStatus(200);
        }

        awaitALaterMail(s, mail());
        assertThat(mail().messagesTo(target.email())).as("mail to " + target.email()).hasSize(mailBefore);
        if (agent) {
            assertThat(pages(target.client(), INBOX)).as("inbox of the agent the requests were about")
                    .isEqualTo(inboxBefore);
        }
    }
}
