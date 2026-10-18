package io.popdesk.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.issue.IssueFlow.World;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.NewIssue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.trash.TrashJobs;
import io.popdesk.trash.TrashSteps;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Automatic cleanup turned off, the default: {@code POPDESK_ISSUE_RETENTION} is not set in the shared "bare" context
 * (S10B-AC-18, S10B-AC-19, S10B-AC-23). Both cleanup jobs of the slice still run and record their runs with 0, and
 * {@value RetentionAppTest#JOB} forgets a wait and the period it saw: the next time the cleanup is turned on is a first
 * time again. The state row of V23 ({@value RetentionAppTest#STATE_TABLE}) is read and written as in
 * {@link RetentionAppTest}.
 */
class RetentionOffTest extends BareAppTest {

    private IssueFlow flow;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
    }

    @Test
    @DisplayName("S10B-AC-18, S10B-AC-23 without POPDESK_ISSUE_RETENTION, issue-retention runs and records 0 and a "
            + "CLOSED issue closed 91 days ago stays where it is; company-export-expiry runs and records 0")
    void offRunsAndMovesNothing() {
        World w = flow.world();
        Issue issue = flow.fileIn(w.reporter(), NewIssue.in(w.code()), w.agent(), "CLOSED");
        Instant closed = IssueFlow.time(flow.detail(w.agent(), issue.key()), "closedAt");

        Instant due = clock.advance(Duration.between(clock.instant(),
                closed.plus(RetentionAppTest.PERIOD).plus(Duration.ofDays(1))));
        Session admin = fixtures.signInAdmin();
        JsonNode retention = TrashJobs.awaitRun(admin, RetentionAppTest.JOB, due);
        JsonNode expiry = TrashJobs.awaitRun(admin, RetentionAppTest.EXPORT_EXPIRY, due);

        assertThat(TrashJobs.count(retention)).as("count of %s: %s", RetentionAppTest.JOB, retention).isZero();
        assertThat(TrashJobs.count(expiry)).as("count of %s: %s", RetentionAppTest.EXPORT_EXPIRY, expiry).isZero();
        assertThat(TrashSteps.entry(admin, issue.key())).as("trash entry of %s", issue.key()).isNull();
        assertThat(flow.detail(admin, issue.key()).path("status").asString(null)).as("status of %s", issue.key())
                .isEqualTo("CLOSED");
    }

    @Test
    @DisplayName("S10B-AC-19 without POPDESK_ISSUE_RETENTION, the next run of issue-retention leaves no period seen and "
            + "no wait (moves_from) in the state row, although it held 90 days and a wait")
    void offForgetsTheWait() {
        assertThat(db.tables()).as("tables of the app (V23 adds %s)", RetentionAppTest.STATE_TABLE)
                .contains(RetentionAppTest.STATE_TABLE);
        Instant waitEnds = clock.instant().plus(Duration.ofDays(10)).truncatedTo(ChronoUnit.SECONDS);
        db.schemaFixture("update " + RetentionAppTest.STATE_TABLE + " set period_seconds = "
                + RetentionAppTest.PERIOD.toSeconds() + ", moves_from = '" + waitEnds + "'::timestamptz");

        Instant next = clock.advance(Duration.ofHours(1).plusMinutes(1));
        TrashJobs.awaitRun(fixtures.signInAdmin(), RetentionAppTest.JOB, next);

        Map<String, Object> state = db.row("select * from " + RetentionAppTest.STATE_TABLE);
        assertThat(state.get("period_seconds")).as("period_seconds of %s after the run", state).isNull();
        assertThat(state.get("moves_from")).as("moves_from of %s after the run: the wait is gone", state).isNull();
    }
}
