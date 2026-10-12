package io.popdesk.inbox;

import static io.popdesk.issue.TalkFlow.isNull;
import static io.popdesk.issue.TalkFlow.kind;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.TalkFlow;
import io.popdesk.manager.ManagerSteps;
import io.popdesk.support.AppLog.Mark;
import io.popdesk.support.ConnectedAppTest;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import io.popdesk.support.Wait;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The admin items from before slice 4 (a membership request, an operational alert) still come and are
 * still listed, and the fields slice 4 added to an item are null in them: no issue, no comment, nobody
 * who did it. What else these items hold is for {@code manager.MembershipRequestMailTest} and
 * {@code ops.OpsAlertTest}.
 */
class AdminInboxItemsTest extends ConnectedAppTest {

    private static final String STATUS = "/api/console/system/status";

    /** The end of the log line the send loop writes after it stored a failed attempt that is tried again. */
    private static final String FIRST_FAILURE_STORED = "It is tried again later.";

    private static JsonNode awaitItem(Session admin, String kind) {
        return Wait.until(kind + " in the inbox of " + admin.email(), () -> TalkFlow.inbox(admin).stream()
                .filter(n -> kind.equals(kind(n))).findFirst().orElse(null));
    }

    private static void assertNoIssueCommentOrActor(JsonNode item) {
        assertThat(isNull(item, "issue")).as("issue is null in %s", item).isTrue();
        assertThat(isNull(item, "commentId")).as("commentId is null in %s", item).isTrue();
        assertThat(isNull(item, "actor")).as("actor is null in %s", item).isTrue();
    }

    @Test
    @DisplayName("S4-AC-38 the item of a membership request is still put in an admin's inbox, with issue, "
            + "commentId, and actor null")
    void membershipRequestItemHasNoIssue() {
        ManagerSteps.Scene s = ManagerSteps.scene(fixtures);
        Session admin = fixtures.admin(s.admin());
        Session target = fixtures.customer(s.admin(), s.other().code());

        ManagerSteps.invited(s.manager(), s.code(), target.email(), Unique.text("Typed"));

        JsonNode item = awaitItem(admin, "MEMBERSHIP_REQUESTED");
        assertThat(item.path("companyCode").asString(null)).as("companyCode of %s", item).isEqualTo(s.code());
        assertNoIssueCommentOrActor(item);
    }

    @Test
    @DisplayName("S4-AC-38 the item of an operational alert is still put in an admin's inbox, with issue, "
            + "commentId, and actor null")
    void deliveryFailedItemHasNoIssue() {
        // As ops.OpsAlertTest: start from a day without a failed row, so that a new failure alerts.
        clock.advance(Duration.ofDays(8));
        Session first = fixtures.signInAdmin();
        Wait.untilAsserted("no failed row left from earlier tests", () -> {
            JsonNode status = first.client().get(STATUS).expect(200).json();
            assertThat(status.path("webhook").path("failed").asLong(-1)).as("webhook.failed").isZero();
            assertThat(status.path("mail").path("failed").asLong(-1)).as("mail.failed").isZero();
        });
        Company company = fixtures.createCompany(first);
        Session customer = fixtures.customer(first, company.code());
        Session admin = fixtures.admin(first);
        Mark mark = log.mark();
        webhook().respondWith(500);
        Issue issue = fixtures.fileIssue(customer, company.code());
        webhook().await("a first attempt for " + issue.key(), r -> r.text().contains(issue.key()));
        // The app writes this line once the first failure and its time are stored. Moving the clock before
        // that would make the time 25 hours on the time of the first failure, and nothing would be given up.
        log.awaitLine(mark, FIRST_FAILURE_STORED);

        clock.advance(Duration.ofHours(25));

        assertNoIssueCommentOrActor(awaitItem(fixtures.signInAgain(admin), "DELIVERY_FAILED"));
    }
}
