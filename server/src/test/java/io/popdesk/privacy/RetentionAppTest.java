package io.popdesk.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.issue.TalkFlow;
import io.popdesk.link.LinkSteps;
import io.popdesk.support.ConnectedAppTest;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.NewIssue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.trash.TrashJobs;
import io.popdesk.trash.TrashRows;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

/**
 * The app of every server test of automatic issue cleanup (slice 10b, PII-6): {@code POPDESK_ISSUE_RETENTION=P90D},
 * the shortest period allowed, in a context and a database of their own, so the issues it moves to the trash are
 * of these tests only. "connected" ({@link ConnectedAppTest}), so a test sees what happens to the issue's rows in
 * the send queue.
 *
 * <p>The app clock ({@link #clock}) moves the 90 days and the 24 hours; a run of {@value #JOB} is read from the
 * system status ({@link TrashJobs}), which carries the time and the count of its record (OPS-2). The context's first
 * run, at its start, finds the cleanup turned on for the first time and waits a day (S10B-AC-19); every test moves
 * the clock past 90 days before it expects a move, which is past any wait an earlier test left.
 *
 * <p>Whether the app saw the cleanup off or with another period before cannot be set in one context, so
 * {@link #lastSeen} writes the period of the state row of V23 ({@code design.md} 1절, {@value #STATE_TABLE}) as the
 * exception the slice allows for a migration's table. Tests share the clock and the trash: they look only at their
 * own issues, and count with the database only where the item they check counts the whole installation.
 */
@TestPropertySource(properties = {RetentionAppTest.OWN_CONTEXT, "POPDESK_ISSUE_RETENTION=P90D"})
public abstract class RetentionAppTest extends ConnectedAppTest {

    /** A property the app does not read; it keeps this context apart from the other connected ones. */
    public static final String OWN_CONTEXT = "privacy.test.context=retention";

    /** The cleanup job that moves issues to the trash (S10B-AC-23). */
    public static final String JOB = "issue-retention";

    /** The cleanup job that deletes old company export files (S10B-AC-08, S10B-AC-23). */
    public static final String EXPORT_EXPIRY = "company-export-expiry";

    /** {@code POPDESK_ISSUE_RETENTION} of this context. */
    public static final Duration PERIOD = Duration.ofDays(90);

    /** How long the cleanup waits after it is turned on or shortened (S10B-AC-19). */
    public static final Duration WAIT = Duration.ofHours(24);

    /** The inbox item of the first day (S10B-AC-19). */
    public static final String ARMED = "ISSUE_RETENTION_ARMED";

    /** The state table of V23 with its one row ({@code design.md} 1절). */
    public static final String STATE_TABLE = "issue_retention";

    /** This context's database container, for tests that hold an issue row. */
    @Autowired
    protected PostgreSQLContainer postgres;

    protected IssueFlow flow;
    protected TrashRows rows;

    @BeforeEach
    void setUpRetention() {
        flow = new IssueFlow(fixtures, clock);
        rows = new TrashRows(db);
    }

    @AfterEach
    void webhookWorksAgain() {
        webhook().respondWith(204);
    }

    /** A new issue of {@code reporter} that {@code agent} took to {@code CLOSED} now. */
    protected Issue closedIssue(Session reporter, Session agent, String code) {
        return flow.fileIn(reporter, NewIssue.in(code).title(LinkSteps.title("Retention")), agent, "CLOSED");
    }

    /** {@code closedAt} of the issue as {@code staff} see it. */
    protected Instant closedAt(Session staff, Issue issue) {
        Instant at = IssueFlow.time(flow.detail(staff, issue.key()), "closedAt");
        assertThat(at).as("closedAt of %s", issue.key()).isNotNull();
        return at;
    }

    /** Moves the clock to {@code at} and waits for the run of {@value #JOB} there; gives its status line. */
    protected JsonNode runAt(Instant at) {
        Duration ahead = Duration.between(clock.instant(), at);
        assertThat(ahead).as("time from %s to %s", clock.instant(), at).isPositive();
        clock.advance(ahead);
        return TrashJobs.awaitRun(fixtures.signInAdmin(), JOB, at);
    }

    /**
     * Writes the period the app saw at its last run into the state row, as if the setting was {@code period} then;
     * null as if it was off. The row's other columns stay.
     */
    protected void lastSeen(Duration period) {
        assertThat(db.tables()).as("tables of the app (V23 adds %s)", STATE_TABLE).contains(STATE_TABLE);
        db.schemaFixture("update " + STATE_TABLE + " set period_seconds = "
                + (period == null ? "null" : String.valueOf(period.toSeconds())));
    }

    /**
     * The issues of the whole installation that are due at {@code at}: {@code CLOSED}, not in the trash, closed or
     * last restored more than {@link #PERIOD} before (acceptance.md 2절 "말의 뜻").
     */
    protected long dueCount(Instant at) {
        return db.count("select count(*) from issue where status = 'CLOSED' and deleted_at is null "
                + "and greatest(closed_at, restored_at) < ?", Timestamp.from(at.minus(PERIOD)));
    }

    /** The {@value #ARMED} items of the caller's console inbox made at or after {@code since}. */
    protected static List<JsonNode> armedSince(Session session, Instant since) {
        return TalkFlow.inbox(session).stream().filter(n -> ARMED.equals(TalkFlow.kind(n)))
                .filter(n -> !IssueFlow.time(n, "createdAt").isBefore(since)).toList();
    }

    /** How many audit entries of {@code action} name the company {@code code} as their target. */
    protected static long auditEntries(Session admin, String code, String action) {
        return admin.client().get("/api/console/audit-log").query("targetCode", code).query("action", action)
                .expect(200).jsonLong("totalElements");
    }
}
