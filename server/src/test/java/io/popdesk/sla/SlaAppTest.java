package io.popdesk.sla;

import io.popdesk.issue.IssueFlow;
import io.popdesk.support.BareAppTest;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.context.TestPropertySource;

/**
 * The app of the SLA tests (design.md 0절 10): a context and a database of its own, so that the one SLA policy of
 * the installation reaches no other test class, and no other class files issues under it. The installation time
 * zone is {@code Asia/Seoul}, which has no daylight saving time. The context starts at {@code 2026-10-08T03:15Z},
 * Thursday 12:15 in Seoul ({@link io.popdesk.support.TestClock#START}).
 *
 * <p>Every SLA class on this context shares the policy. A test first sets the whole policy it needs
 * ({@link SlaSteps#change}, with the version it read) and counts only the issues it filed itself; it never relies
 * on a policy another class left.
 *
 * <p>A class that needs another time zone adds its own {@code @TestPropertySource} with {@code POPDESK_TIMEZONE}
 * and overrides {@link #zone()}; that is another context.
 */
@TestPropertySource(properties = {"POPDESK_TIMEZONE=" + SlaAppTest.SEOUL, SlaAppTest.OWN_CONTEXT})
public abstract class SlaAppTest extends BareAppTest {

    public static final String SEOUL = "Asia/Seoul";

    /** A property the app does not read; it keeps this context apart from {@code due.SeoulZoneTest}'s. */
    public static final String OWN_CONTEXT = "sla.test.context=sla";

    /** Steps of the SLA policy and of the {@code sla} of issues, in {@link #zone()}. */
    protected SlaSteps sla;

    /** Issue steps of the earlier slices (status, edit, list, filing on behalf). */
    protected IssueFlow flow;

    /** The installation time zone of this context, as {@code POPDESK_TIMEZONE} sets it. */
    protected ZoneId zone() {
        return ZoneId.of(SEOUL);
    }

    @BeforeEach
    void setUpSla() {
        flow = new IssueFlow(fixtures, clock);
        sla = new SlaSteps(fixtures, clock, zone());
    }
}
