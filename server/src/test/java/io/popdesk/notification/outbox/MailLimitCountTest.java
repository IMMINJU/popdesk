package io.popdesk.notification.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The 50 emails an hour to one address, by the limit alone: a clock the test moves and a registry of
 * its own. Written from the acceptance criteria and the signatures, not from the body of the class.
 */
class MailLimitCountTest {

    private static final String COUNTER = "popdesk.mail.rate.limited";
    private static final String ADDRESS = "zed@example.test";
    private static final Duration A_MOMENT = Duration.ofMillis(1);

    private final MovedClock clock = new MovedClock(Instant.parse("2026-10-13T09:00:00Z"));
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MailLimit limit = new MailLimit(clock, registry);

    /** A clock that stands still until the test moves it. */
    private static final class MovedClock extends Clock {
        private Instant now;

        MovedClock(Instant start) {
            this.now = start;
        }

        void forward(Duration by) {
            now = now.plus(by);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    private void send(String address, int emails) {
        for (int i = 0; i < emails; i++) {
            limit.sent(address);
        }
    }

    /** How many emails the limit says it stopped; none when the counter is not there yet. */
    private double stopped() {
        Counter counter = registry.find(COUNTER).counter();
        return counter == null ? 0 : counter.count();
    }

    // S4B-AC-10

    @Test
    @DisplayName("S4B-AC-10 the limit is 50 emails in 60 minutes and its error kind is RATE_LIMITED")
    void theLimitIsFiftyAnHour() {
        assertThat(MailLimit.LIMIT).isEqualTo(50);
        assertThat(MailLimit.WINDOW).isEqualTo(Duration.ofMinutes(60));
        assertThat(MailLimit.ERROR_KIND).isEqualTo("RATE_LIMITED");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 49})
    @DisplayName("S4B-AC-10 an address that got fewer than 50 emails is not stopped")
    void fewerThanFiftyIsNotStopped(int emails) {
        send(ADDRESS, emails);

        assertThat(limit.stops(ADDRESS)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {50, 51, 120})
    @DisplayName("S4B-AC-10 an address that got 50 emails or more within the hour is stopped")
    void fiftyIsStopped(int emails) {
        send(ADDRESS, emails);

        assertThat(limit.stops(ADDRESS)).isTrue();
    }

    @Test
    @DisplayName("S4B-AC-10 the 50th email is the one that closes the limit: open after 49, closed after one more")
    void theFiftiethClosesTheLimit() {
        send(ADDRESS, 49);
        assertThat(limit.stops(ADDRESS)).as("after 49").isFalse();

        limit.sent(ADDRESS);

        assertThat(limit.stops(ADDRESS)).as("after 50").isTrue();
    }

    @Test
    @DisplayName("S4B-AC-10 50 emails to one address do not stop another address, and 49 to each of two stop neither")
    void addressesAreCountedApart() {
        send(ADDRESS, 50);
        send("amy@example.test", 49);
        send("bob@example.test", 49);

        assertThat(limit.stops(ADDRESS)).as("the address with 50").isTrue();
        assertThat(limit.stops("amy@example.test")).as("one with 49").isFalse();
        assertThat(limit.stops("bob@example.test")).as("the other with 49").isFalse();
        assertThat(limit.stops("new@example.test")).as("an address with none").isFalse();
    }

    @ParameterizedTest
    @CsvSource({
        "zed@example.test, ZED@EXAMPLE.TEST",
        "ZED@EXAMPLE.TEST, zed@example.test",
        "Zed@Example.Test, zeD@exAmple.tEst"
    })
    @DisplayName("S4B-AC-10 an address is one address in any case: emails sent in one spelling stop the other")
    void caseOfTheAddressDoesNotMatter(String sentTo, String asked) {
        send(sentTo, 50);

        assertThat(limit.stops(asked)).isTrue();
    }

    @Test
    @DisplayName("S4B-AC-10 emails to two spellings of one address add up to the 50")
    void spellingsOfOneAddressAddUp() {
        send("zed@example.test", 25);
        send("ZED@Example.Test", 24);
        assertThat(limit.stops("Zed@example.test")).as("after 49 in two spellings").isFalse();

        limit.sent("zed@EXAMPLE.test");

        assertThat(limit.stops("zed@example.test")).as("after 50 in three spellings").isTrue();
    }

    @Test
    @DisplayName("S4B-AC-10 an address stays stopped up to the end of the 60 minutes")
    void stoppedUntilTheHourIsOver() {
        send(ADDRESS, 50);

        clock.forward(MailLimit.WINDOW.minus(A_MOMENT));

        assertThat(limit.stops(ADDRESS)).isTrue();
    }

    // S4B-AC-11

    @Test
    @DisplayName("S4B-AC-11 once the 60 minutes are over the address is not stopped any more")
    void openAgainAfterTheHour() {
        send(ADDRESS, 50);

        clock.forward(MailLimit.WINDOW.plus(A_MOMENT));

        assertThat(limit.stops(ADDRESS)).isFalse();
    }

    @Test
    @DisplayName("S4B-AC-11 after the hour the count starts over: 49 new emails are let through and the 50th closes "
            + "the limit again")
    void countStartsOverAfterTheHour() {
        send(ADDRESS, 50);
        clock.forward(MailLimit.WINDOW.plus(A_MOMENT));

        send(ADDRESS, 49);
        assertThat(limit.stops(ADDRESS)).as("49 in the new hour").isFalse();
        limit.sent(ADDRESS);

        assertThat(limit.stops(ADDRESS)).as("50 in the new hour").isTrue();
    }

    @Test
    @DisplayName("S4B-AC-11 the 60 minutes slide: of 50 emails a minute apart the oldest leaves first, and each "
            + "email that leaves makes room for one")
    void theHourSlides() {
        for (int i = 0; i < 50; i++) {
            if (i > 0) {
                clock.forward(Duration.ofMinutes(1));
            }
            limit.sent(ADDRESS);
        }
        // the first went 49 minutes ago, the last just now
        assertThat(limit.stops(ADDRESS)).as("50 within 49 minutes").isTrue();

        clock.forward(Duration.ofMinutes(11).minus(A_MOMENT));
        assertThat(limit.stops(ADDRESS)).as("a moment before the first email is 60 minutes old").isTrue();

        clock.forward(A_MOMENT.multipliedBy(2));
        assertThat(limit.stops(ADDRESS)).as("the first email is over 60 minutes old, 49 are left").isFalse();

        limit.sent(ADDRESS);
        assertThat(limit.stops(ADDRESS)).as("one more makes 50 again").isTrue();

        clock.forward(Duration.ofMinutes(1));
        assertThat(limit.stops(ADDRESS)).as("the second email is over 60 minutes old too").isFalse();
    }

    @Test
    @DisplayName("S4B-AC-11 only the emails of the last 60 minutes count: 30 old ones and 49 new ones do not stop, "
            + "one more new one does")
    void oldEmailsDoNotCount() {
        send(ADDRESS, 30);
        clock.forward(MailLimit.WINDOW.plus(A_MOMENT));

        send(ADDRESS, 49);
        assertThat(limit.stops(ADDRESS)).as("30 old and 49 new").isFalse();
        limit.sent(ADDRESS);

        assertThat(limit.stops(ADDRESS)).as("30 old and 50 new").isTrue();
    }

    @Test
    @DisplayName("S4B-AC-11 the hour of one address ending does not open another address")
    void theHourEndsForEachAddressApart() {
        send(ADDRESS, 50);
        clock.forward(Duration.ofMinutes(30));
        send("amy@example.test", 50);

        clock.forward(Duration.ofMinutes(30).plus(A_MOMENT));

        assertThat(limit.stops(ADDRESS)).as("the address whose emails are over 60 minutes old").isFalse();
        assertThat(limit.stops("amy@example.test")).as("the address whose emails are 30 minutes old").isTrue();
    }

    @Test
    @DisplayName("S4B-AC-11 with emails to more than 10000 addresses the count of each address still holds, before "
            + "and after their hour")
    void manyAddresses() {
        for (int i = 0; i < 10_050; i++) {
            limit.sent("many" + i + "@example.test");
        }
        send("many7@example.test", 49);
        assertThat(limit.stops("many7@example.test")).as("an address with 50 among many").isTrue();
        assertThat(limit.stops("many8@example.test")).as("an address with 1 among many").isFalse();

        clock.forward(MailLimit.WINDOW.plus(A_MOMENT));
        send(ADDRESS, 50);
        send("many8@example.test", 49);

        assertThat(limit.stops("many7@example.test")).as("its hour is over").isFalse();
        assertThat(limit.stops("many8@example.test")).as("49 in the new hour").isFalse();
        assertThat(limit.stops(ADDRESS)).as("50 in the new hour").isTrue();
        limit.sent("many8@example.test");
        assertThat(limit.stops("many8@example.test")).as("50 in the new hour").isTrue();
        assertThat(stopped()).as("emails stopped").isEqualTo(3.0);
    }

    // S4B-AC-12

    @Test
    @DisplayName("S4B-AC-12 each email the limit stops adds one to popdesk.mail.rate.limited, which has no label")
    void eachStoppedEmailIsCounted() {
        send(ADDRESS, 50);

        assertThat(limit.stops(ADDRESS)).isTrue();
        assertThat(stopped()).as("after one stopped email").isEqualTo(1.0);
        assertThat(limit.stops(ADDRESS)).isTrue();
        assertThat(limit.stops(ADDRESS)).isTrue();

        assertThat(stopped()).as("after three stopped emails").isEqualTo(3.0);
        assertThat(registry.find(COUNTER).counters()).as("counters of that name").hasSize(1);
        assertThat(registry.get(COUNTER).counter().getId().getTags()).as("labels").isEmpty();
    }

    @Test
    @DisplayName("S4B-AC-12 an email that is let through adds nothing to the counter, nor does an email that was sent")
    void emailsLetThroughAreNotCounted() {
        assertThat(limit.stops(ADDRESS)).as("no email yet").isFalse();
        send(ADDRESS, 49);
        assertThat(limit.stops(ADDRESS)).as("49 emails").isFalse();
        assertThat(stopped()).as("stopped before the limit").isZero();

        limit.sent(ADDRESS);
        assertThat(stopped()).as("stopped when the 50th was sent but none was asked for").isZero();
        assertThat(limit.stops("amy@example.test")).as("another address").isFalse();

        assertThat(stopped()).as("stopped after another address was let through").isZero();
    }

    @Test
    @DisplayName("S4B-AC-12 the counter adds up the stopped emails of all addresses and keeps its count after the "
            + "hour is over")
    void counterAddsUpOverAddresses() {
        send(ADDRESS, 50);
        send("amy@example.test", 50);
        assertThat(limit.stops(ADDRESS)).isTrue();
        assertThat(limit.stops("amy@example.test")).isTrue();
        assertThat(limit.stops("AMY@example.test")).isTrue();

        clock.forward(MailLimit.WINDOW.plus(A_MOMENT));
        assertThat(limit.stops(ADDRESS)).as("after the hour").isFalse();

        assertThat(stopped()).isEqualTo(3.0);
    }

    // S4B-AC-13

    @Test
    @DisplayName("S4B-AC-13 only emails the server took count: asking about an address any number of times does not "
            + "use up its 50")
    void askingDoesNotCount() {
        for (int i = 0; i < 200; i++) {
            assertThat(limit.stops(ADDRESS)).as("asked %s times, none sent", i + 1).isFalse();
        }
        send(ADDRESS, 49);
        for (int i = 0; i < 200; i++) {
            assertThat(limit.stops(ADDRESS)).as("asked %s times, 49 sent", i + 1).isFalse();
        }
        assertThat(stopped()).isZero();
    }

    @Test
    @DisplayName("S4B-AC-13 an email that was stopped does not count as sent: the address opens 60 minutes after its "
            + "50th email however often it was stopped since")
    void stoppedEmailsDoNotCount() {
        send(ADDRESS, 50);
        for (int i = 0; i < 59; i++) {
            clock.forward(Duration.ofMinutes(1));
            assertThat(limit.stops(ADDRESS)).as("%s minutes after the 50 emails", i + 1).isTrue();
        }

        clock.forward(Duration.ofMinutes(1).plus(A_MOMENT));

        assertThat(limit.stops(ADDRESS)).as("60 minutes after the 50 emails").isFalse();
        assertThat(stopped()).isEqualTo(59.0);
    }

    @Test
    @DisplayName("S4B-AC-13 the count goes by the address the email went to: all email of a sandbox goes to one "
            + "address and adds up there")
    void emailToOneAddressAddsUp() {
        String sandbox = "ops@example.test";
        for (int i = 0; i < 25; i++) {
            // two people's email, both sent to the sandbox address
            limit.sent(sandbox);
            limit.sent(sandbox);
        }

        assertThat(limit.stops(sandbox)).as("the address the 50 went to").isTrue();
        assertThat(limit.stops("amy@example.test")).as("an address nothing went to").isFalse();
    }
}
