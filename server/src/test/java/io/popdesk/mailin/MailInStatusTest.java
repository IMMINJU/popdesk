package io.popdesk.mailin;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiResponse;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import io.popdesk.support.Wait;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import tools.jackson.databind.JsonNode;

/** Mail-in in the system status (OPS-3) and in the metrics (OPS). */
class MailInStatusTest extends MailInAppTest {

    static final Set<String> FIELDS = Set.of("enabled", "offReason", "lastSuccessAt", "roundFailures", "lastFailure",
            "retrying", "quarantined90d");

    @Autowired
    private Environment environment;

    @Test
    @DisplayName("S12-AC-36 the admin's status has mailIn with exactly its seven fields: on, no off reason, the time of the last good round, round failures, the last failure with kind and time, retrying, and quarantined in 90 days")
    void statusFields() {
        Instant round = rounds.next();
        JsonNode good = rounds.mailIn();
        assertThat(fieldNames(good)).as("fields of mailIn").isEqualTo(FIELDS);
        assertThat(good.path("enabled").isBoolean() && good.path("enabled").booleanValue()).as("enabled in %s", good)
                .isTrue();
        assertThat(good.path("offReason").isNull()).as("offReason in %s", good).isTrue();
        assertThat(OffsetDateTime.parse(good.path("lastSuccessAt").asString()).toInstant()).as("lastSuccessAt")
                .isAfterOrEqualTo(round);
        for (String count : Set.of("roundFailures", "retrying", "quarantined90d")) {
            assertThat(good.path(count).isIntegralNumber()).as("%s in %s", count, good).isTrue();
            assertThat(good.path(count).asInt(-1)).as(count).isNotNegative();
        }
        mailIn().cut();
        String kind;
        try {
            kind = rounds.nextFailing();
        } finally {
            mailIn().restore();
        }
        JsonNode failed = rounds.mailIn();
        assertThat(kind).as("lastFailure.kind").isEqualTo("CONNECT");
        assertThat(fieldNames(failed.path("lastFailure"))).as("fields of lastFailure").isEqualTo(Set.of("kind", "at"));
        assertThat(OffsetDateTime.parse(failed.path("lastFailure").path("at").asString()).toInstant())
                .as("lastFailure.at").isAfter(round);
        assertThat(failed.path("roundFailures").asInt(-1)).as("roundFailures after a failed round")
                .isGreaterThan(good.path("roundFailures").asInt(-1));
        assertThat(failed.path("lastSuccessAt")).as("lastSuccessAt after a failed round")
                .isEqualTo(good.path("lastSuccessAt"));
        rounds.next();
    }

    @Test
    @DisplayName("S12-AC-36 an agent gets 403 for the system status with mail-in on")
    void agentIsRefused() {
        Session agent = agent();

        ApiResponse status = agent.client().get(Rounds.STATUS).send();

        assertThat(status.status()).as("%s %s", status, status.snippet()).isEqualTo(403);
        assertThat(status.json().path("code").asString(null)).as("code").isEqualTo("FORBIDDEN");
    }

    @Test
    @DisplayName("S12-AC-35 a mail that becomes an issue counts in popdesk.mailin.messages with outcome ISSUE")
    void messagesMetric() {
        MailInMetrics metrics = new MailInMetrics(environment);
        Session sender = customer(company().code());
        send(mailFrom(sender).text("Counted " + Unique.number()));

        rounds.next();

        assertThat(issuesOf(sender)).as("issues of the sender").hasSize(1);
        Wait.untilAsserted("popdesk_mailin_messages with outcome ISSUE", () ->
                assertThat(metrics.sum("popdesk_mailin_messages", "outcome=\"ISSUE\"")).as("ISSUE messages")
                        .isGreaterThanOrEqualTo(1));
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.propertyNames().forEach(names::add);
        return names;
    }
}
