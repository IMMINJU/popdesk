package io.popdesk.mailin;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;

/** What of a mail becomes the content of the issue or the comment (MAILIN-4). */
class MailBodyTest extends MailInAppTest {

    static final String TOO_LONG = "(The email was too long; only its beginning was kept.)";

    /** The content of the one issue a new customer files with this mail. */
    private String issueContent(Function<Inbound, Inbound> mail) {
        Company company = company();
        Session sender = customer(company.code());
        send(mail.apply(mailFrom(sender)));
        rounds.next();
        return onlyIssueOf(sender).path("content").asString("");
    }

    /** The content of the comment a new customer adds to their own issue with this mail (by the key in the subject). */
    private String commentContent(Function<Inbound, Inbound> mail) {
        Company company = company();
        Session sender = customer(company.code());
        Issue issue = fixtures.fileIssue(sender, company.code());
        send(mail.apply(mailFrom(sender).subject("Re: [" + issue.key() + "] " + Unique.number())));
        rounds.next();
        List<JsonNode> mine = comments(issue.key()).stream()
                .filter(c -> c.path("author").path("accountId").asLong(-1) == sender.accountId()).toList();
        assertThat(mine).as("comments of the sender on " + issue.key()).hasSize(1);
        return mine.get(0).path("content").asString("");
    }

    static Stream<Arguments> bodies() {
        long n = Unique.number();
        String html = "<html><head><style>p { color: red }</style><title>Mail title</title></head><body>"
                + "<p>Hello &amp; welcome " + n + "</p><p>Line<br>two</p><script>alert('x')</script>"
                + "<a href=\"https://example.com/help\">help page</a></body></html>";
        return Stream.of(
                Arguments.of("text and HTML: the text",
                        (Function<Inbound, Inbound>) m -> m.text("Plain " + n).html("<p>Html " + n + "</p>"),
                        (Consumer<String>) c -> assertThat(c).isEqualTo("Plain " + n)),
                Arguments.of("HTML only: as text", (Function<Inbound, Inbound>) m -> m.html(html),
                        (Consumer<String>) c -> assertThat(c).contains("Hello & welcome " + n).contains("Line\ntwo")
                                .contains("help page (https://example.com/help)")
                                .doesNotContain("alert").doesNotContain("color").doesNotContain("Mail title")
                                .doesNotContain("<").doesNotContain("&amp;")),
                Arguments.of("text next to an attached message: not the message's text",
                        (Function<Inbound, Inbound>) m -> m.text("Outer " + n)
                                .attachMessage(Inbound.mail().from("old@mail.example").text("Inner " + n)),
                        (Consumer<String>) c -> assertThat(c).isEqualTo("Outer " + n)),
                Arguments.of("only an attached message: no content",
                        (Function<Inbound, Inbound>) m -> m.attachMessage(
                                Inbound.mail().from("old@mail.example").text("Inner " + n)),
                        (Consumer<String>) c -> assertThat(c).isEqualTo("(no content)")),
                Arguments.of("charset=x-bogus: read anyway",
                        (Function<Inbound, Inbound>) m -> m.text("Bogus charset " + n).charset("x-bogus"),
                        (Consumer<String>) c -> assertThat(c).isEqualTo("Bogus charset " + n)),
                Arguments.of("empty body", (Function<Inbound, Inbound>) m -> m.text(""),
                        (Consumer<String>) c -> assertThat(c).isEqualTo("(no content)")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bodies")
    @DisplayName("S12-AC-22 the content is the first text/plain part, else the HTML as text; an attached message is not the body; a broken charset is no error; empty is \"(no content)\"")
    void contentFromBody(String what, Function<Inbound, Inbound> mail, Consumer<String> check) {
        String content = issueContent(mail);

        check.accept(content);
    }

    static Stream<Arguments> quotes() {
        return Stream.of(
                Arguments.of("On … wrote:", "On Mon, Oct 19, 2026 at 2:00 PM Hong Gildong <hong@mail.example> wrote:\n"
                        + "> The old text"),
                Arguments.of("… 님이 작성:", "2026년 10월 19일 (월) 오후 2:00, 홍길동 <a@b>님이 작성:\n> 예전 글"),
                Arguments.of("-----Original Message-----", "-----Original Message-----\nFrom: Support\nThe old text"),
                Arguments.of("-----원본 메시지-----", "-----원본 메시지-----\n보낸 사람: 지원팀\n예전 글"),
                Arguments.of("> lines at the end", "> The old text\n> more of it"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("quotes")
    @DisplayName("S12-AC-23 a comment by mail loses the quote from its separator line on, and the blank lines before it")
    void commentLosesQuote(String what, String quote) {
        String answer = "My answer " + Unique.number();

        String content = commentContent(m -> m.text(answer + "\n\n" + quote + "\n"));

        assertThat(content).as("comment content, " + what).isEqualTo(answer);
    }

    @Test
    @DisplayName("S12-AC-23 a new issue by mail keeps the same quote")
    void newIssueKeepsQuote() {
        String body = "Forwarded " + Unique.number() + "\n\n-----Original Message-----\nFrom: Someone\nThe old text";

        String content = issueContent(m -> m.text(body));

        assertThat(content).as("issue content").contains("-----Original Message-----").contains("The old text");
    }

    static Stream<Arguments> limits() {
        return Stream.of(Arguments.of("issue", 10_000), Arguments.of("comment", 4_000));
    }

    @ParameterizedTest(name = "{0} of {1} characters")
    @MethodSource("limits")
    @DisplayName("S12-AC-24 a body over the limit (issue 10,000, comment 4,000) is cut from its start so that it ends with the closing line within the limit")
    void longBodyIsCut(String where, int limit) {
        StringBuilder body = new StringBuilder("Start " + Unique.number());
        while (body.length() < limit + 500) {
            body.append(" word");
        }
        Function<Inbound, Inbound> mail = m -> m.text(body.toString());

        String content = where.equals("issue") ? issueContent(mail) : commentContent(mail);

        assertThat(content.length()).as("length of the " + where).isLessThanOrEqualTo(limit);
        assertThat(content).as(where + " content").endsWith(TOO_LONG).startsWith(body.substring(0, limit - 200));
    }
}
