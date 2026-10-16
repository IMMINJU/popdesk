package io.popdesk.sla;

import static io.popdesk.sla.SlaSteps.FIRST_RESPONSE;
import static io.popdesk.sla.SlaSteps.RESOLUTION;
import static io.popdesk.sla.SlaSteps.assertGoal;
import static io.popdesk.sla.SlaSteps.createdAt;
import static io.popdesk.sla.SlaSteps.goal;
import static io.popdesk.sla.SlaSteps.sla;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.issue.TalkFlow;
import io.popdesk.sla.SlaSteps.Desk;
import io.popdesk.sla.SlaSteps.Policy;
import io.popdesk.support.ApiRequest;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.Concurrently;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.HeldWrite;
import io.popdesk.support.Wait;
import io.popdesk.trash.TrashJobs;
import io.popdesk.trash.TrashRows;
import io.popdesk.trash.TrashSteps;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

/**
 * A write to an issue while {@code sla-recalculate} comes for it (S8A-AC-21, STATUS-7). After a new version the
 * issue waits for the job. A first response, a status change, another priority, or a delete is sent and kept waiting
 * at its UPDATE of the issue row ({@link HeldWrite}), inside its transaction, holding the row lock it took before
 * (design.md 0절 4); the clock moves a minute so the job runs; the write is let go once the job ran (it skips the
 * locked row), waits for the row, or is held at the same row too. Every day counts 24 hours here.
 *
 * <p>The write took the issue first, so the result is the write's, worked out with the new version. A job that reads
 * the issue without locking it and writes what it read without asking whether the calculation version is still the
 * one it read waits in the trigger too, and once let go writes its stale values after the write: the first response
 * runs again, a resolved issue's resolution runs again, the old priority's due times come back, or a paused clock in
 * the trash runs.
 *
 * <p>A clock write also meets a policy change (design.md 0절 8): the change is kept waiting at its insert of the new
 * version, after it took the version row and read the time; a first response sent then waits for the change and
 * counts with the new version from that time. A first response that does not wait finishes with the old version
 * alone.
 */
class SlaRaceTest extends SlaAppTest {

    private static final Policy BEFORE = Policy.off().target("NORMAL", 60, 600).target("HIGH", 30, 240);
    private static final Policy AFTER = Policy.off().target("NORMAL", 60, 900).target("HIGH", 30, 240);

    private static final String HELD = "select count(*) from pg_stat_activity where wait_event = 'PgSleep' "
            + "and pid <> pg_backend_pid() and datname = current_database()";

    @Autowired
    PostgreSQLContainer postgres;

    private TrashRows rows;

    @BeforeEach
    void setUpRows() {
        rows = new TrashRows(db);
    }

    @Test
    @DisplayName("S8A-AC-21 a first response sent while a policy change that read the time 59:40 after filing is "
            + "kept from committing ends after the change: the 40 seconds after it count with the new version, which "
            + "makes today a holiday, so the answer at 60:20 is MET against a goal of 60 minutes")
    void writeMeetsAPolicyChange() {
        Instant filedAt = sla.moveToNext(DayOfWeek.WEDNESDAY, LocalTime.of(9, 0));
        LocalDate today = sla.local(filedAt).toLocalDate();
        Desk d = sla.desk();
        SlaSteps.change(d.admin(), Policy.off().target("NORMAL", 60, 600));
        Issue issue = sla.file(d, "NORMAL");
        // The job runs at 59:30 and not again before 60:30, so it takes no part.
        clock.advance(Duration.ofSeconds(58 * 60 + 30));
        sla.recalculateNow(d.admin());
        clock.advance(Duration.ofSeconds(10));
        Map<String, Object> body = Policy.off().holiday(today).target("NORMAL", 60, 600)
                .body(SlaSteps.version(SlaSteps.policy(d.admin())));
        ApiResponse saved;
        ApiResponse answered;

        try (HeldWrite hold = new HeldWrite(db, postgres, "sla_policy", "insert", "")) {
            CompletableFuture<ApiResponse> saving = CompletableFuture.supplyAsync(
                    () -> SlaSteps.changeRequest(d.admin(), body).send());
            hold.awaitHeld("the new version of the policy change");
            clock.advance(Duration.ofSeconds(40));
            CompletableFuture<ApiResponse> answer = CompletableFuture.supplyAsync(
                    () -> TalkFlow.addRequest(d.agent(), issue.key(), TalkFlow.body("content", "First answer")).send());
            Wait.until("the first response finished or waits for a lock",
                    () -> answer.isDone() || hold.waitingForALock() > 0 ? Boolean.TRUE : null);
            hold.release();
            saved = Concurrently.finish(saving, "the policy change");
            answered = Concurrently.finish(answer, "the first response");
        }

        assertThat(saved.status()).as("the policy change: %s", saved.snippet()).isEqualTo(200);
        assertThat(answered.status()).as("the first response: %s", answered.snippet()).isEqualTo(201);
        assertGoal(goal(sla(d.agent(), issue.key()), FIRST_RESPONSE), "MET",
                createdAt(issue).plus(Duration.ofMinutes(60)), null, "first response answered at 60:20");
    }

    /** The write sent while the job comes. */
    enum Write {
        ANSWER,
        RESOLVE,
        PRIORITY,
        DELETE;

        ApiRequest request(Desk d, String key, long version) {
            return switch (this) {
                case ANSWER -> TalkFlow.addRequest(d.agent(), key, TalkFlow.body("content", "First answer"));
                case RESOLVE -> IssueFlow.statusRequest(d.agent(), key, "RESOLVED");
                case PRIORITY -> IssueFlow.editRequest(d.agent(), key, Map.of("priority", "HIGH",
                        "version", version));
                case DELETE -> TrashSteps.deleteRequest(d.admin(), key);
            };
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Write.class)
    @DisplayName("S8A-AC-21 a first response, a resolve, another priority, or a delete that holds the issue while "
            + "sla-recalculate runs ends as if the write came first and the job after: the job does not write its "
            + "stale values over the write")
    void writeMeetsTheJob(Write write) {
        Desk d = sla.desk();
        SlaSteps.change(d.admin(), BEFORE);
        Issue issue = sla.file(d, "NORMAL");
        String key = issue.key();
        Instant filed = createdAt(issue);
        if (write == Write.RESOLVE) {
            flow.moveTo(d.agent(), key, "IN_PROGRESS");
        }
        sla.advanceMinutes(10);
        sla.recalculateNow(d.admin());
        SlaSteps.change(d.admin(), AFTER);
        long version = flow.version(d.agent(), key);
        ApiRequest request = write.request(d, key, version);
        ApiResponse written;

        try (HeldWrite hold = new HeldWrite(db, postgres, "issue", "update", issue.json().path("title").asString())) {
            CompletableFuture<ApiResponse> sent = CompletableFuture.supplyAsync(request::send);
            hold.awaitHeld("the UPDATE of the issue by the write");
            Instant jobAt = sla.advanceMinutes(1);
            Wait.until("sla-recalculate ran, waits for the row, or waits in the trigger too", TrashJobs.RUN,
                    () -> TrashJobs.run(d.admin(), SlaSteps.JOB, jobAt) != null || hold.waitingForALock() > 0
                            || db.count(HELD) >= 2 ? Boolean.TRUE : null);
            hold.release();
            written = Concurrently.finish(sent, "the write");
        }
        assertThat(written.status()).as("the write: %s", written.snippet()).isBetween(200, 204);
        sla.recalculateNow(d.admin());

        switch (write) {
            case ANSWER -> {
                JsonNode after = sla(d.agent(), key);
                assertGoal(goal(after, FIRST_RESPONSE), "MET", filed.plus(Duration.ofMinutes(60)), null,
                        "first response");
                assertThat(SlaSteps.dueAt(goal(after, RESOLUTION))).as("resolution due time with the new version")
                        .isEqualTo(filed.plus(Duration.ofMinutes(900)));
            }
            case RESOLVE -> {
                JsonNode after = sla(d.agent(), key);
                for (String name : new String[] {FIRST_RESPONSE, RESOLUTION}) {
                    assertThat(SlaSteps.state(goal(after, name))).as("state of %s after resolving", name)
                            .isEqualTo("MET");
                    assertThat(SlaSteps.remainingMinutes(goal(after, name)))
                            .as("minutes left of %s after resolving", name).isNull();
                }
            }
            case PRIORITY -> {
                JsonNode after = sla(d.agent(), key);
                assertThat(SlaSteps.dueAt(goal(after, FIRST_RESPONSE))).as("first response due time as HIGH")
                        .isEqualTo(filed.plus(Duration.ofMinutes(30)));
                assertThat(SlaSteps.dueAt(goal(after, RESOLUTION))).as("resolution due time as HIGH")
                        .isEqualTo(filed.plus(Duration.ofMinutes(240)));
            }
            case DELETE -> {
                Map<String, Object> row = rows.issue(key);
                for (String column : new String[] {"fr_since", "res_since", "sla_due_at", "sla_warn_at",
                        "fr_outcome", "res_outcome"}) {
                    assertThat(row.get(column)).as("%s of the issue in the trash", column).isNull();
                }
                assertThat(((Number) row.get("res_spent")).longValue()).as("res_spent of the issue in the trash")
                        .isEqualTo(Duration.ofMinutes(11).toSeconds());
                Instant restoredAt = sla.now().toInstant();
                TrashSteps.restore(d.admin(), key);
                assertThat(SlaSteps.dueAt(goal(sla(d.agent(), key), RESOLUTION))).as("resolution due after restore")
                        .isEqualTo(restoredAt.plus(Duration.ofMinutes(900 - 11)));
            }
        }
    }
}
