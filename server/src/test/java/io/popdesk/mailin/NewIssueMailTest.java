package io.popdesk.mailin;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import io.popdesk.support.Wait;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;

/** A mail that is no reply becomes a new issue of its sender (MAILIN-3, ISSUE-2). */
class NewIssueMailTest extends MailInAppTest {

    @Test
    @DisplayName("S12-AC-20 a new issue by mail: the sender reports it, priority NORMAL, public, channel EMAIL, the body as content, and the webhook message of a portal issue")
    void newIssueFields() {
        Company company = company();
        Session sender = customer(company.code());
        String subject = "Printer jams " + Unique.number();
        String content = "It jams after toner change " + Unique.number();

        send(mailFrom(sender).subject(subject).text(content));
        rounds.next();

        JsonNode issue = onlyIssueOf(sender);
        assertThat(issue.path("title").asString("")).as("title").isEqualTo(subject);
        assertThat(issue.path("content").asString("")).as("content").isEqualTo(content);
        assertThat(issue.path("companyCode").asString("")).as("company").isEqualTo(company.code());
        assertThat(issue.path("reporter").path("accountId").asLong(-1)).as("reporter").isEqualTo(sender.accountId());
        assertThat(issue.path("priority").asString("")).as("priority").isEqualTo("NORMAL");
        assertThat(issue.path("private").asBoolean(true)).as("private").isFalse();
        assertThat(issue.path("channel").asString("")).as("channel").isEqualTo("EMAIL");
        String key = issue.path("key").asString();
        Wait.untilAsserted("the new-issue webhook message", () ->
                assertThat(webhook().containing(key)).as("webhook messages about " + key)
                        .anySatisfy(r -> assertThat(r.text()).contains("New issue " + key + " from " + company.code())));
    }

    static Stream<Arguments> subjects() {
        long n = Unique.number();
        String longSubject = "Long " + n + " " + "x".repeat(250);
        return Stream.of(
                Arguments.of("encoded word", false, (Function<Inbound, Inbound>) m -> m.subject("결제 오류 " + n),
                        "결제 오류 " + n),
                Arguments.of("line break and control characters", false,
                        (Function<Inbound, Inbound>) m -> m.subject("Line1\nLine2\u0007end x " + n),
                        "Line1 Line2 end x " + n),
                Arguments.of("over 200 characters", false, (Function<Inbound, Inbound>) m -> m.subject(longSubject),
                        longSubject.substring(0, 200)),
                Arguments.of("empty, Korean account", true, (Function<Inbound, Inbound>) m -> m.rawSubject(""),
                        "(제목 없음)"),
                Arguments.of("no Subject header, English account", false, (Function<Inbound, Inbound>) Inbound::noSubject,
                        "(no subject)"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("subjects")
    @DisplayName("S12-AC-20 the issue title is the decoded subject, control characters as spaces, at most 200 characters, or \"(no subject)\" in the account's language")
    void titleFromSubject(String what, boolean korean, Function<Inbound, Inbound> subject, String title) {
        Company company = company();
        Session sender = customer(company.code());
        if (korean) {
            korean(sender);
        }

        send(subject.apply(mailFrom(sender)).text("Body " + Unique.number()));
        rounds.next();

        assertThat(onlyIssueOf(sender).path("title").asString("")).as("title, " + what).isEqualTo(title);
    }
}
