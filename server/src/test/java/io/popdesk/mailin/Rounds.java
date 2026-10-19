package io.popdesk.mailin;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.TestClock;
import io.popdesk.support.Wait;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import tools.jackson.databind.JsonNode;

/**
 * Mail-in rounds (S12-AC-06): the app reads the mailbox right after it starts and then once a minute by its clock. A
 * round here is "move the shared clock a minute, then wait until {@code mailIn} of the system status says a round
 * ended at or after that time" (never a fixed wait). Mail put in the mailbox before {@link #next()} is read in that
 * round.
 *
 * <p>Before it moves the clock, a step waits until the last round, good or bad, ended within the last minute of the
 * clock, so a round still running from an earlier move cannot be taken for the new one. Move the clock for mail-in
 * only through these steps; after any other move of more than a minute, call {@link #next()} once before
 * delivering. While mail-in is off ({@code mailIn.enabled} false) every step fails at once, on an assertion.
 */
public final class Rounds {

    public static final String STATUS = "/api/console/system/status";

    /** The time between two rounds. */
    public static final Duration ROUND = Duration.ofMinutes(1);

    /** How long one round may take: up to 100 mails, some with files. */
    static final Duration WAIT = Duration.ofSeconds(30);

    private final Session admin;
    private final TestClock clock;

    /** Steps that read the system status as {@code admin} and move {@code clock}. */
    public Rounds(Session admin, TestClock clock) {
        this.admin = admin;
        this.clock = clock;
    }

    /** {@code mailIn} of {@code GET /api/console/system/status}, as the admin. */
    public JsonNode mailIn() {
        return admin.client().get(STATUS).expect(200).json().path("mailIn");
    }

    /** {@code mailIn.lastSuccessAt}, or null. */
    public Instant lastSuccessAt() {
        return time(mailIn().path("lastSuccessAt"));
    }

    /** {@code mailIn.roundFailures}. */
    public int roundFailures() {
        return mailIn().path("roundFailures").asInt(-1);
    }

    /** {@code mailIn.lastFailure.kind}, or null. */
    public String lastFailureKind() {
        return mailIn().path("lastFailure").path("kind").asString(null);
    }

    /** One good round: moves the clock a minute and waits until a round ended at or after the new time. */
    public Instant next() {
        settle();
        Instant moved = clock.advance(ROUND);
        Wait.until("a mail-in round at " + moved, WAIT, () -> {
            Instant last = lastSuccessAt();
            return last != null && !last.isBefore(moved) ? last : null;
        });
        return moved;
    }

    /** {@code times} good rounds, one after another. */
    public void next(int times) {
        for (int i = 0; i < times; i++) {
            next();
        }
    }

    /**
     * One failed round: moves the clock a minute and waits until {@code roundFailures} went up. Returns the kind of
     * the last failure ({@code CONNECT}, {@code AUTH}, {@code FOLDER}, {@code UNSUPPORTED}).
     */
    public String nextFailing() {
        settle();
        int before = roundFailures();
        Instant moved = clock.advance(ROUND);
        Wait.until("a failed mail-in round at " + moved, WAIT, () -> roundFailures() > before ? Boolean.TRUE : null);
        return lastFailureKind();
    }

    /** Waits until mail-in is on and its last round, good or bad, ended within the last minute of the clock. */
    private void settle() {
        JsonNode status = mailIn();
        assertThat(status.path("enabled").asBoolean(false)).as("mailIn.enabled in " + status).isTrue();
        Wait.until("a mail-in round within the last minute of " + clock.instant(), WAIT, () -> {
            JsonNode now = mailIn();
            Instant last = later(time(now.path("lastSuccessAt")), time(now.path("lastFailure").path("at")));
            return last != null && last.isAfter(clock.instant().minus(ROUND)) ? last : null;
        });
    }

    private static Instant later(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        return b == null || a.isAfter(b) ? a : b;
    }

    private static Instant time(JsonNode node) {
        String text = node.asString(null);
        return text == null || node.isNull() ? null : OffsetDateTime.parse(text).toInstant();
    }
}
