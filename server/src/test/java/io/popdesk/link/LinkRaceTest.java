package io.popdesk.link;

import static io.popdesk.link.LinkSteps.DUPLICATE_OF;
import static io.popdesk.link.LinkSteps.LIMIT;
import static io.popdesk.link.LinkSteps.RELATED;
import static io.popdesk.link.LinkSteps.described;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.link.LinkSteps.World;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Concurrently;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Wait;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Links that arrive at the same time (S6C-AC-08, LINK-2, STATUS-7, {@code design.md} 0절 1 and 3절): the same two
 * issues from both sides, a duplicate chain, and the last link under the limit. Each kind is sent many times, since
 * one round may not overlap; only the number of each answer is counted, so it does not matter which request wins.
 * The app retries a transaction once after a deadlock ({@code conventions.md} "Retrying on lock"), so the answers
 * alone do not show one: the test reads {@code pg_stat_database.deadlocks} before and after ({@code acceptance.md}
 * 4절).
 */
class LinkRaceTest extends BareAppTest {

    private static final int ROUNDS = 20;
    private static final int CHAIN_ROUNDS = 10;
    private static final int AT_ONCE = 8;

    /** Deadlocks the database server reported for the app's database so far. */
    private static final String DEADLOCKS = "select deadlocks from pg_stat_database where datname = current_database()";

    /** The statistics are written a moment after the transaction ends; a deadlock shows within this time. */
    private static final Duration STATS_DELAY = Duration.ofSeconds(12);

    private IssueFlow flow;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
    }

    private static String describe(List<ApiResponse> answers) {
        return answers.stream().map(a -> a.status() + (a.status() < 300 ? "" : " " + a.errorCode())).toList()
                .toString();
    }

    private static long count(List<ApiResponse> answers, int status, String code) {
        return answers.stream().filter(a -> a.status() == status && (code == null || code.equals(a.errorCode())))
                .count();
    }

    @Test
    @DisplayName("S6C-AC-08 linking two issues from both sides at the same time, 20 times over: each time one 201 and one 409 LINK_EXISTS, one link, and the database reports no deadlock")
    void bothSidesAtOnce() {
        World w = LinkSteps.world(fixtures, flow);
        Session other = fixtures.agent(w.admin(), w.a().code(), w.b().code());
        List<Issue> ofA = LinkSteps.fileMany(fixtures, clock, w.reporterA(), w.a().code(), ROUNDS);
        List<Issue> ofB = LinkSteps.fileMany(fixtures, clock, w.reporterB(), w.b().code(), ROUNDS);
        long deadlocks = db.count(DEADLOCKS);
        List<String> wrong = new ArrayList<>();

        for (int i = 0; i < ROUNDS; i++) {
            String a = ofA.get(i).key();
            String b = ofB.get(i).key();
            List<ApiResponse> answers = Concurrently.send(2, List.of(
                    () -> LinkSteps.createRequest(w.linker(), a, b, RELATED).send(),
                    () -> LinkSteps.createRequest(other, b, a, DUPLICATE_OF).send()));
            if (count(answers, 201, null) != 1 || count(answers, 409, "LINK_EXISTS") != 1) {
                wrong.add("round " + i + ": " + describe(answers));
            }
        }

        assertThat(wrong).as("rounds without exactly one 201 and one 409").isEmpty();
        for (int i = 0; i < ROUNDS; i++) {
            assertThat(described(w.admin(), ofA.get(i).key())).as("links of %s", ofA.get(i).key()).hasSize(1);
        }
        Wait.holds("no deadlock reported", STATS_DELAY, () -> db.count(DEADLOCKS) == deadlocks);
    }

    @Test
    @DisplayName("S6C-AC-08 linking A as a duplicate of B and B as a duplicate of C at the same time, 10 times over: each time one 201 and one 400 LINK_DUPLICATE_CHAIN, and no issue is both a duplicate and an original")
    void chainAtOnce() {
        World w = LinkSteps.world(fixtures, flow);
        Session other = fixtures.agent(w.admin(), w.a().code(), w.b().code());
        List<Issue> issues = LinkSteps.fileMany(fixtures, clock, w.reporterA(), w.a().code(), 3 * CHAIN_ROUNDS);
        long deadlocks = db.count(DEADLOCKS);
        List<String> wrong = new ArrayList<>();

        for (int i = 0; i < CHAIN_ROUNDS; i++) {
            String a = issues.get(3 * i).key();
            String b = issues.get(3 * i + 1).key();
            String c = issues.get(3 * i + 2).key();
            List<ApiResponse> answers = Concurrently.send(2, List.of(
                    () -> LinkSteps.createRequest(w.linker(), a, b, DUPLICATE_OF).send(),
                    () -> LinkSteps.createRequest(other, b, c, DUPLICATE_OF).send()));
            List<String> ofB = described(w.admin(), b);
            if (count(answers, 201, null) != 1 || count(answers, 400, "LINK_DUPLICATE_CHAIN") != 1
                    || ofB.size() != 1) {
                wrong.add("round " + i + ": " + describe(answers) + ", links of the middle issue " + ofB);
            }
        }

        assertThat(wrong).as("rounds without exactly one 201, one LINK_DUPLICATE_CHAIN, and one link").isEmpty();
        Wait.holds("no deadlock reported", STATS_DELAY, () -> db.count(DEADLOCKS) == deadlocks);
    }

    @Test
    @DisplayName("S6C-AC-08 with 49 links on an issue, 8 links to it from new issues at the same time: one 201, seven 400 LINK_LIMIT_EXCEEDED, and the issue has 50")
    void lastLinkAtOnce() {
        World w = LinkSteps.world(fixtures, flow);
        List<Issue> issues = LinkSteps.fileMany(fixtures, clock, w.reporterA(), w.a().code(), LIMIT + AT_ONCE);
        Issue hub = issues.getFirst();
        for (Issue each : issues.subList(1, LIMIT)) {
            LinkSteps.link(w.linker(), each.key(), hub.key(), RELATED);
        }
        List<Supplier<ApiResponse>> calls = new ArrayList<>();
        for (Issue each : issues.subList(LIMIT, LIMIT + AT_ONCE)) {
            Session caller = fixtures.agent(w.admin(), w.a().code());
            calls.add(() -> LinkSteps.createRequest(caller, each.key(), hub.key(), RELATED).send());
        }

        List<ApiResponse> answers = Concurrently.send(AT_ONCE, calls);

        assertThat(count(answers, 201, null)).as("201 in %s", describe(answers)).isEqualTo(1);
        assertThat(count(answers, 400, "LINK_LIMIT_EXCEEDED")).as("LINK_LIMIT_EXCEEDED in %s", describe(answers))
                .isEqualTo(AT_ONCE - 1L);
        assertThat(described(w.admin(), hub.key())).as("links of the issue").hasSize(LIMIT);
    }
}
