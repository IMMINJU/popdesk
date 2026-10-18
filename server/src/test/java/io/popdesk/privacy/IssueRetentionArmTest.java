package io.popdesk.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow.World;
import io.popdesk.issue.TalkFlow;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.trash.TrashSteps;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;

/**
 * The first day (S10B-AC-19, OPS-1): when the cleanup is turned on, or its period is shorter than the one the app saw
 * last, the next run moves nothing, leaves every admin an {@value #ARMED} item, and the moves start 24 hours later.
 * A period as long as the last one or longer starts no wait. What the app saw last is written into the state row
 * ({@link #lastSeen}); this context's setting stays {@code P90D}.
 *
 * <p>The item's count is the number of due issues of the whole installation at the run, read from the database
 * ({@link #dueCount}) after it, while the clock stands still and nothing was moved.
 */
class IssueRetentionArmTest extends RetentionAppTest {

    /** What the app saw at its last run before this one. */
    enum Before {
        OFF(null),
        LONGER(Duration.ofDays(100)),
        AS_LONG_IN_HOURS(Duration.ofHours(2160)),
        SHORTER(Duration.ofDays(80));

        final Duration period;

        Before(Duration period) {
            this.period = period;
        }
    }

    /** A due issue, a second admin, and an agent of the company, all made before the run. */
    private record Scene(World world, Session otherAdmin, Issue issue, Instant closedAt) {}

    private Scene scene() {
        World w = flow.world();
        Session otherAdmin = fixtures.admin(w.admin());
        Issue issue = closedIssue(w.reporter(), w.agent(), w.code());
        return new Scene(w, otherAdmin, issue, closedAt(w.agent(), issue));
    }

    /** The one item of the run at {@code at} in the inbox of {@code admin}. */
    private JsonNode armedItem(Session admin, Instant at) {
        List<JsonNode> items = armedSince(admin, at);
        assertThat(items).as("%s items of %s since %s", ARMED, admin.email(), at).hasSize(1);
        JsonNode item = items.getFirst();
        assertThat(item.path("retentionDays").asInt(-1)).as("retentionDays of %s", item).isEqualTo(90);
        assertThat(item.path("issue").isNull()).as("issue of %s", item).isTrue();
        assertThat(item.path("companyCode").isNull()).as("companyCode of %s", item).isTrue();
        return item;
    }

    private void assertLive(Issue issue, String when) {
        Session admin = fixtures.signInAdmin();
        assertThat(TrashSteps.entry(admin, issue.key())).as("trash entry of %s %s", issue.key(), when).isNull();
        assertThat(flow.detail(admin, issue.key()).path("status").asString(null)).as("status of %s %s", issue.key(),
                when).isEqualTo("CLOSED");
    }

    private void assertMoved(Issue issue, String when) {
        JsonNode entry = TrashSteps.entry(fixtures.signInAdmin(), issue.key());
        assertThat(entry).as("trash entry of %s %s", issue.key(), when).isNotNull();
        assertThat(entry.path("deletedBy").isNull()).as("deletedBy of %s", entry).isTrue();
    }

    @ParameterizedTest(name = "{0} before")
    @EnumSource(value = Before.class, names = {"OFF", "LONGER"})
    @DisplayName("S10B-AC-19 when the cleanup was off or longer at the last run, the next run moves no issue and "
            + "leaves every admin, and no agent, one ISSUE_RETENTION_ARMED item with the due issues and 90 days; "
            + "23 hours later the issue is still there, 24 hours later it is moved")
    void turnedOnOrShortenedWaitsADay(Before before) {
        Scene s = scene();
        lastSeen(before.period);

        Instant armedAt = s.closedAt().plus(PERIOD).plus(Duration.ofDays(1));
        runAt(armedAt);

        long due = dueCount(armedAt);
        assertThat(due).as("due issues of the installation at the run, ours among them").isPositive();
        Session admin = fixtures.signInAdmin();
        JsonNode item = armedItem(admin, armedAt);
        assertThat(item.path("retentionCount").asLong(-1)).as("retentionCount of %s", item).isEqualTo(due);
        JsonNode other = armedItem(fixtures.signInAgain(s.otherAdmin()), armedAt);
        assertThat(other.path("retentionCount").asLong(-1)).as("retentionCount of %s", other).isEqualTo(due);
        assertThat(TalkFlow.kinds(armedSince(fixtures.signInAgain(s.world().agent()), armedAt)))
                .as("%s items of the agent", ARMED).isEmpty();
        assertLive(s.issue(), "after the run that armed");

        runAt(armedAt.plus(WAIT).minus(Duration.ofHours(1)));
        assertLive(s.issue(), "23 hours after the run that armed");

        Instant movedAt = armedAt.plus(WAIT).plus(Duration.ofMinutes(1));
        runAt(movedAt);
        assertMoved(s.issue(), "24 hours after the run that armed");
    }

    @ParameterizedTest(name = "{0} before")
    @EnumSource(value = Before.class, names = {"AS_LONG_IN_HOURS", "SHORTER"})
    @DisplayName("S10B-AC-19 when the period at the last run was as long (2160 hours) or shorter, the next run starts "
            + "no wait: it moves the due issue and leaves no ISSUE_RETENTION_ARMED item")
    void asLongOrLongerStartsNoWait(Before before) {
        Scene s = scene();
        lastSeen(before.period);

        Instant at = s.closedAt().plus(PERIOD).plus(Duration.ofDays(1));
        runAt(at);

        assertMoved(s.issue(), "after the run");
        assertThat(armedSince(fixtures.signInAdmin(), at)).as("%s items since the run", ARMED).isEmpty();
    }

    /** How the setting compares with the period written as seen last, half a day into the wait. */
    enum During {
        SHORTENED,
        LENGTHENED
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(During.class)
    @DisplayName("S10B-AC-19 during the wait, a setting shorter than the period seen last starts the 24 hours again with a new "
            + "ISSUE_RETENTION_ARMED item, and a longer one keeps the wait as it was: nothing moves before it ends")
    void changeDuringTheWait(During during) {
        Scene s = scene();
        lastSeen(null);
        Instant armedAt = s.closedAt().plus(PERIOD).plus(Duration.ofDays(1));
        runAt(armedAt);
        armedItem(fixtures.signInAdmin(), armedAt);
        runAt(armedAt.plus(Duration.ofHours(12)));

        lastSeen(during == During.SHORTENED ? Duration.ofDays(100) : Duration.ofDays(80));
        Instant changedAt = armedAt.plus(Duration.ofHours(13));
        runAt(changedAt);

        Instant firstEnd = armedAt.plus(WAIT).plus(Duration.ofMinutes(1));
        if (during == During.SHORTENED) {
            armedItem(fixtures.signInAdmin(), changedAt);
            assertLive(s.issue(), "after the shorter period");
            runAt(firstEnd);
            assertLive(s.issue(), "24 hours after the first item, 11 after the second");
            runAt(changedAt.plus(WAIT).plus(Duration.ofMinutes(1)));
            assertMoved(s.issue(), "24 hours after the second item");
        } else {
            assertThat(armedSince(fixtures.signInAdmin(), changedAt)).as("%s items since the longer period", ARMED)
                    .isEmpty();
            assertLive(s.issue(), "after the longer period");
            runAt(armedAt.plus(WAIT).minus(Duration.ofHours(1)));
            assertLive(s.issue(), "23 hours after the first item, before its wait ends");
            runAt(firstEnd);
            assertMoved(s.issue(), "24 hours after the first item");
        }
    }
}
