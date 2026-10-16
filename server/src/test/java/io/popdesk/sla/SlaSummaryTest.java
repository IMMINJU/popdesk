package io.popdesk.sla;

import static io.popdesk.sla.SlaAlertSteps.RESOLUTION_WARNING;
import static io.popdesk.sla.SlaAlertSteps.RESPONSE_BREACHED;
import static io.popdesk.sla.SlaAlertSteps.RESPONSE_WARNING;
import static io.popdesk.sla.SlaAlertSteps.alertKinds;
import static io.popdesk.sla.SlaAlertSteps.assertAlert;
import static io.popdesk.sla.SlaAlertSteps.assertSummary;
import static io.popdesk.sla.SlaAlertSteps.oneAlert;
import static io.popdesk.sla.SlaAlertSteps.summaries;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.sla.SlaSteps.Desk;
import io.popdesk.sla.SlaSteps.Policy;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.ReceivedMail;
import io.popdesk.support.WebhookReceiver.Received;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The summary of a recalculation (S8B-AC-12 to S8B-AC-15): a policy change is counted per issue as newly breached,
 * newly in warning, and released, the issues counted are not alerted one by one, and when the recalculation ends
 * every active admin gets one {@code SLA_RECALCULATED} item with the three counts, no mail and no webhook message.
 * Runs on the mail app so that "no mail" and "no webhook message" can be read. Every day counts 24 hours here.
 *
 * <p>A policy change works out every running issue of the context again, those of other classes too, so a summary
 * would count them. Their stage is kept the same: the test first moves the clock a day on, so every running issue of
 * another class is more than a day old, and both policies give every priority a resolution goal of at most 100
 * minutes. Such an issue is breached before and after the change, which is no change. Two runs after that the other
 * issues are worked out and alerted, and the admins that read the summary are made after them.
 */
class SlaSummaryTest extends SlaMailAppTest {

    /** Before the change: first response and resolution goals in minutes. */
    private static Policy before() {
        return Policy.off().target("LOW", null, 100).target("NORMAL", 50, 100).target("HIGH", 50, 100)
                .target("URGENT", 20, 100);
    }

    /** After the change: NORMAL answers sooner, HIGH has no first response goal, URGENT resolves sooner. */
    private static Policy after() {
        return Policy.off().target("LOW", null, 100).target("NORMAL", 20, 100).target("HIGH", null, 100)
                .target("URGENT", 20, 40);
    }

    /** Moves the clock a day on, sets {@link #before()}, and waits two runs (see the class comment). */
    private void otherIssuesSettle() {
        clock.advance(Duration.ofDays(1));
        Session admin = sla.admin();
        SlaSteps.change(admin, before());
        alerts.runs(admin, 2);
    }

    // S8B-AC-12, S8B-AC-13, S8B-AC-14, S8B-AC-15

    @Test
    @DisplayName("S8B-AC-12, S8B-AC-13, S8B-AC-14, S8B-AC-15 a change that moves no issue leaves no summary; a change "
            + "that breaches two issues (one caught up by a status change before the job ran), puts one in warning, "
            + "and takes away the first response goal of one in warning gives every admin one SLA_RECALCULATED item "
            + "with 2, 1, 1 and nothing else in it, no item to the agent, no mail, and no webhook message; the "
            + "counted issues are not alerted one by one, an issue already overdue in its first response whose "
            + "resolution comes into warning is not counted and gets the resolution warning, and the one counted in "
            + "warning is alerted when it is overdue later")
    void aPolicyChangeIsOneSummary() {
        otherIssuesSettle();
        Desk d = sla.desk();
        Session watcher = alerts.newAdmin(d.admin());
        Session another = alerts.newAdmin(d.admin());
        // The same policy with a holiday half a year on: a new version that moves no issue to another step.
        SlaSteps.change(d.admin(), before().holiday(sla.now().toLocalDate().plusMonths(6)));
        Instant start = sla.recalculateNow(d.admin());

        // The change comes 50 minutes after the start. Ages then: 45, 35, 34, 30, 17.
        alerts.runAt(d.admin(), start.plus(Duration.ofMinutes(5)));
        Issue high = sla.file(d, "HIGH");
        alerts.runAt(d.admin(), start.plus(Duration.ofMinutes(15)));
        Issue byTheJob = sla.file(d, "NORMAL");
        alerts.runAt(d.admin(), start.plus(Duration.ofMinutes(16)));
        Issue urgent = sla.file(d, "URGENT");
        alerts.runAt(d.admin(), start.plus(Duration.ofMinutes(20)));
        Issue caughtUp = sla.file(d, "NORMAL");
        alerts.runAt(d.admin(), start.plus(Duration.ofMinutes(33)));
        Issue warned = sla.file(d, "NORMAL");
        alerts.runAt(d.admin(), start.plus(Duration.ofMinutes(48)));
        alerts.runs(d.admin(), 2);
        assertThat(alertKinds(d.agent(), urgent.key())).as("SLA items of the URGENT issue before the change")
                .containsExactlyInAnyOrder(RESPONSE_WARNING, RESPONSE_BREACHED);
        assertThat(alertKinds(d.agent(), high.key())).as("SLA items of the HIGH issue before the change")
                .containsExactly(RESPONSE_WARNING);
        webhook().await("the overdue message of " + urgent.key(),
                r -> r.text().contains(urgent.key()) && r.text().contains("SLA"));
        mail().awaitMessage("the overdue mail of " + urgent.key() + " to an admin",
                m -> m.to().contains(watcher.email()) && m.subject().contains(urgent.key())
                        && m.subject().contains("SLA"));
        int webhooksBefore = webhook().requests().size();
        int mailBefore = mail().messagesTo(watcher.email()).size();

        SlaSteps.change(d.admin(), after());
        // Before the job runs: the write catches the issue up with the new version, 30 minutes against 20.
        flow.changeStatus(d.agent(), caughtUp.key(), "CONFIRMED");
        alerts.runs(d.admin(), 2);
        alerts.runAfterFiling(d.admin(), warned, 21);

        mail().awaitMessage("the overdue mail of " + warned.key() + " to an admin",
                m -> m.to().contains(watcher.email()) && m.subject().contains(warned.key())
                        && m.subject().contains("SLA"));
        webhook().await("the overdue message of " + warned.key(),
                r -> r.text().contains(warned.key()) && r.text().contains("SLA"));
        for (Session admin : List.of(watcher, another)) {
            List<JsonNode> items = summaries(admin);
            assertThat(items).as("SLA_RECALCULATED items of the admin %s", admin.email()).hasSize(1);
            assertSummary(items.getFirst(), 2, 1, 1);
        }
        assertThat(summaries(d.agent())).as("SLA_RECALCULATED items of the agent").isEmpty();
        for (Session person : List.of(d.agent(), watcher)) {
            for (Issue counted : List.of(byTheJob, caughtUp)) {
                assertThat(alertKinds(person, counted.key())).as("SLA items of %s, counted as newly breached, for %s",
                        counted.key(), person.role()).isEmpty();
            }
        }
        assertThat(alertKinds(d.agent(), warned.key())).as("SLA items of the issue counted in warning, overdue later")
                .containsExactly(RESPONSE_BREACHED);
        assertThat(alertKinds(d.agent(), high.key())).as("SLA items of the released HIGH issue")
                .containsExactly(RESPONSE_WARNING);
        assertThat(alertKinds(d.agent(), urgent.key()))
                .as("SLA items of the URGENT issue, overdue in its first response, its resolution in warning")
                .containsExactlyInAnyOrder(RESPONSE_WARNING, RESPONSE_BREACHED, RESOLUTION_WARNING);
        assertAlert(oneAlert(d.agent(), urgent.key(), RESOLUTION_WARNING), RESOLUTION_WARNING, urgent.key(),
                d.code());

        List<ReceivedMail> mailAfter = mail().messagesTo(watcher.email());
        assertThat(mailAfter.subList(mailBefore, mailAfter.size())).as("mail to the admin after the change")
                .extracting(ReceivedMail::subject).singleElement().asString().contains(warned.key());
        List<Received> webhooksAfter = webhook().requests();
        assertThat(webhooksAfter.subList(webhooksBefore, webhooksAfter.size()).stream()
                .filter(r -> !r.text().contains(caughtUp.key())).toList())
                .as("webhook messages after the change, but for the status of " + caughtUp.key())
                .extracting(Received::text).singleElement().asString().contains(warned.key());
    }
}
