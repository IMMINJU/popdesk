package io.popdesk.trash;

import static io.popdesk.issue.IssueFlow.time;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.issue.IssueFlow.World;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.NewIssue;
import io.popdesk.support.Fixtures.Session;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The cleanup job {@code issue-auto-close} (STATUS-5): every 10 minutes it closes the live {@code RESOLVED} issues
 * whose time to close from is 7 days ago or more, as the system (S7A-AC-40); that time is set when an issue becomes
 * {@code RESOLVED} and set again when it is resolved again (S7A-AC-41); an issue in the trash is not closed
 * (S7A-AC-11). The app clock moves the days; the job's run is read from the system status ({@link TrashJobs}).
 */
class AutoCloseTest extends BareAppTest {

    private static final Duration SEVEN_DAYS = Duration.ofDays(7);

    private IssueFlow flow;
    private TrashRows rows;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
        rows = new TrashRows(db);
    }

    /** Moves the app clock to {@code at}, which is not before it. */
    private Instant moveTo(Instant at) {
        return clock.advance(Duration.between(clock.instant(), at));
    }

    private Issue resolved(World w) {
        return flow.fileIn(w.reporter(), NewIssue.in(w.code()), w.agent(), "RESOLVED");
    }

    private String status(Session staff, String key) {
        return flow.detail(staff, key).path("status").asString(null);
    }

    @Test
    @DisplayName("S7A-AC-40 a RESOLVED issue stays RESOLVED until 7 days after it was resolved and is CLOSED by the next run after that, as the system (actor 0) with a history entry; issues in the other statuses stay; the job's record has the run and a count")
    void closesSevenDaysAfterResolving() {
        World w = flow.world();
        Issue issue = resolved(w);
        String key = issue.key();
        Instant resolvedAt = time(flow.detail(w.agent(), key), "resolvedAt");
        Map<String, String> others = new LinkedHashMap<>();
        for (String status : List.of("RECEIVED", "CONFIRMED", "IN_PROGRESS", "CLOSED")) {
            others.put(flow.fileIn(w.reporter(), NewIssue.in(w.code()), w.agent(), status).key(), status);
        }
        int entries = rows.history(key).size();

        Instant almost = moveTo(resolvedAt.plus(SEVEN_DAYS.minusMinutes(1)));
        Session admin = fixtures.signInAgain(w.admin());
        TrashJobs.awaitRun(admin, TrashJobs.AUTO_CLOSE, almost);
        assertThat(status(admin, key)).as("status a minute before 7 days").isEqualTo("RESOLVED");

        Instant due = clock.advance(Duration.ofMinutes(11));
        JsonNode run = TrashJobs.awaitRun(admin, TrashJobs.AUTO_CLOSE, due, 1);

        assertThat(TrashJobs.lastRunAt(run)).as("time of the run that closed").isEqualTo(due);
        JsonNode closed = flow.detail(admin, key);
        assertThat(closed.path("status").asString(null)).as("status 10 minutes after 7 days").isEqualTo("CLOSED");
        assertThat(time(closed, "closedAt")).as("closedAt").isEqualTo(due);
        List<Map<String, Object>> history = rows.history(key);
        assertThat(history).as("history rows after the closing").hasSize(entries + 1);
        Map<String, Object> last = history.getLast();
        assertThat(last.get("field")).as("field of " + last).isEqualTo("STATUS");
        assertThat(last.get("old_value")).as("old value of " + last).isEqualTo("RESOLVED");
        assertThat(last.get("new_value")).as("new value of " + last).isEqualTo("CLOSED");
        assertThat(((Number) last.get("actor_id")).longValue()).as("actor of " + last).isZero();
        assertThat(TrashRows.instant(last.get("created_at"))).as("time of " + last).isEqualTo(due);
        others.forEach((other, status) -> assertThat(status(admin, other)).as("status of " + other)
                .isEqualTo(status));
    }

    @Test
    @DisplayName("S7A-AC-41 an issue resolved, reopened 3 days later, and resolved again is not closed 7 days after the first resolving but 7 days after the second")
    void resolvingAgainSetsANewTime() {
        World w = flow.world();
        Issue issue = resolved(w);
        String key = issue.key();
        clock.advance(Duration.ofDays(3));
        flow.reopen(fixtures.signInAgain(w.reporter()), key);
        Session agent = fixtures.signInAgain(w.agent());
        flow.moveTo(agent, key, "RESOLVED");
        Instant resolvedAgain = time(flow.detail(agent, key), "resolvedAt");

        Instant almost = moveTo(resolvedAgain.plus(SEVEN_DAYS.minusMinutes(1)));
        Session admin = fixtures.signInAgain(w.admin());
        TrashJobs.awaitRun(admin, TrashJobs.AUTO_CLOSE, almost);
        assertThat(status(admin, key)).as("status 10 days after the first resolving, a minute before 7 days after "
                + "the second").isEqualTo("RESOLVED");

        Instant due = clock.advance(Duration.ofMinutes(11));
        TrashJobs.awaitRun(admin, TrashJobs.AUTO_CLOSE, due, 1);
        assertThat(status(admin, key)).as("status 10 minutes after 7 days after the second resolving")
                .isEqualTo("CLOSED");
    }

    @Test
    @DisplayName("S7A-AC-11 a RESOLVED issue in the trash is not closed after 7 days, while one resolved at the same time and not deleted is")
    void issueInTheTrashIsNotClosed() {
        World w = flow.world();
        Issue gone = resolved(w);
        Issue kept = resolved(w);
        Instant resolvedAt = time(flow.detail(w.agent(), kept.key()), "resolvedAt");
        TrashSteps.delete(w.admin(), gone.key());

        Instant due = moveTo(resolvedAt.plus(SEVEN_DAYS.plusMinutes(11)));
        Session admin = fixtures.signInAgain(w.admin());
        TrashJobs.awaitRun(admin, TrashJobs.AUTO_CLOSE, due, 1);

        assertThat(status(admin, kept.key())).as("status of the issue not deleted").isEqualTo("CLOSED");
        JsonNode entry = TrashSteps.entry(admin, gone.key());
        assertThat(entry).as("trash entry of " + gone.key()).isNotNull();
        assertThat(entry.path("status").asString(null)).as("status of the issue in the trash").isEqualTo("RESOLVED");
        assertThat(rows.issue(gone.key()).get("status")).as("status column of the issue in the trash")
                .isEqualTo("RESOLVED");
    }
}
