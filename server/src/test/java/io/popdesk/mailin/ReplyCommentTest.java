package io.popdesk.mailin;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.issue.TalkFlow;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.MailInServer.Folder;
import io.popdesk.support.Unique;
import io.popdesk.support.Wait;
import java.util.List;
import java.util.Locale;
import java.util.function.BiFunction;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;

/** A reply by mail becomes a public comment of the customer (MAILIN-2): by the notification's Message-ID or by the key. */
class ReplyCommentTest extends MailInAppTest {

    /** One company, its customer who files, an agent assigned to it, and an issue of the customer the agent has. */
    private record Scene(Company company, Session customer, Session agent, Issue issue) {}

    private Scene scene() {
        Company company = company();
        Session customer = customer(company.code());
        Session agent = agent(company.code());
        Issue issue = fixtures.fileIssue(customer, company.code());
        IssueFlow.assignRequest(admin, issue.key(), agent.accountId()).expect(200);
        return new Scene(company, customer, agent, issue);
    }

    /** The comment on the issue whose content is {@code content}; fails unless there is exactly one. */
    private JsonNode commentWith(String key, String content) {
        List<JsonNode> found = comments(key).stream().filter(c -> content.equals(c.path("content").asString(""))).toList();
        assertThat(found).as("comments on " + key + " with content \"" + content + "\": " + comments(key)).hasSize(1);
        return found.get(0);
    }

    private void assertCustomerComment(JsonNode comment, Session customer) {
        assertThat(comment.path("author").path("accountId").asLong(-1)).as("author of " + comment)
                .isEqualTo(customer.accountId());
        assertThat(comment.path("internal").asBoolean(true)).as("internal of " + comment).isFalse();
    }

    static Stream<Arguments> pointers() {
        return Stream.of(
                Arguments.of("In-Reply-To", (BiFunction<Inbound, String, Inbound>) Inbound::inReplyTo),
                Arguments.of("References only", (BiFunction<Inbound, String, Inbound>) (m, id) -> m.references(
                        Inbound.newMessageId(), id)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("pointers")
    @DisplayName("S12-AC-15 a reply naming the notification's Message-ID becomes the customer's public comment, told to the assignee as a portal comment is")
    void replyByMessageId(String way, BiFunction<Inbound, String, Inbound> pointAt) {
        Scene s = scene();
        String notification = messageIdOf(staffAnswer(s.agent(), s.customer(), s.issue().key()));
        String content = "Reply " + Unique.number();
        Inbound reply = pointAt.apply(mailFrom(s.customer()).subject("Re: thanks " + Unique.number()).text(content),
                notification);

        send(reply);
        rounds.next();

        JsonNode comment = commentWith(s.issue().key(), content);
        assertCustomerComment(comment, s.customer());
        Wait.untilAsserted("CUSTOMER_COMMENT in the assignee's inbox", () ->
                assertThat(TalkFlow.inboxOf(s.agent(), s.issue().key()))
                        .as("inbox of the assignee about " + s.issue().key())
                        .anySatisfy(n -> {
                            assertThat(TalkFlow.kind(n)).isEqualTo("CUSTOMER_COMMENT");
                            assertThat(n.path("commentId").asLong(-1)).isEqualTo(comment.path("id").asLong());
                        }));
        Wait.untilAsserted("a customer-comment webhook message", () ->
                assertThat(webhook().containing(s.issue().key())).as("webhook messages about " + s.issue().key())
                        .anySatisfy(r -> assertThat(r.text()).contains("Customer comment")));
        assertThat(issuesOf(s.customer())).as("issues of the customer").hasSize(1);
        assertThat(foldersOf(reply)).as("folders of the reply").containsExactly(Folder.PROCESSED);
    }

    @Test
    @DisplayName("S12-AC-15 References naming another company's notification and two of the customer's own: the comment goes to the last own one")
    void lastVisibleReference() {
        Scene s = scene();
        Issue earlier = s.issue();
        Issue later = fixtures.fileIssue(s.customer(), s.company().code());
        Scene other = scene();
        String earlierId = messageIdOf(staffAnswer(s.agent(), s.customer(), earlier.key()));
        String laterId = messageIdOf(staffAnswer(s.agent(), s.customer(), later.key()));
        String foreignId = messageIdOf(staffAnswer(other.agent(), other.customer(), other.issue().key()));
        String content = "Reply " + Unique.number();

        send(mailFrom(s.customer()).subject("Re: thanks " + Unique.number()).references(earlierId, laterId, foreignId)
                .text(content));
        rounds.next();

        assertCustomerComment(commentWith(later.key(), content), s.customer());
        assertThat(comments(earlier.key())).as("comments on the earlier issue")
                .noneMatch(c -> content.equals(c.path("content").asString("")));
        assertThat(comments(other.issue().key())).as("comments on the other company's issue")
                .noneMatch(c -> content.equals(c.path("content").asString("")));
    }

    static Stream<Arguments> subjects() {
        return Stream.of(
                Arguments.of("Re: [%s] the printer"),
                Arguments.of("AW: Fwd: [%s] the printer"),
                Arguments.of("Re: [%s] lower case"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("subjects")
    @DisplayName("S12-AC-16 a mail with [key] in the subject, in any case, becomes the customer's comment on that issue")
    void replyByKey(String form) {
        Scene s = scene();
        String key = form.contains("lower") ? s.issue().key().toLowerCase(Locale.ROOT) : s.issue().key();
        String content = "Reply " + Unique.number();
        Inbound reply = mailFrom(s.customer()).subject(String.format(Locale.ROOT, form, key) + " " + Unique.number())
                .text(content);

        send(reply);
        rounds.next();

        assertCustomerComment(commentWith(s.issue().key(), content), s.customer());
        assertThat(issuesOf(s.customer())).as("issues of the customer").hasSize(1);
        assertThat(foldersOf(reply)).as("folders of the reply").containsExactly(Folder.PROCESSED);
    }

    static Stream<Arguments> notKeys() {
        return Stream.of(Arguments.of("%s-0"), Arguments.of("%s-1234567890"));
    }

    @ParameterizedTest(name = "[{0}]")
    @MethodSource("notKeys")
    @DisplayName("S12-AC-16 brackets that are not of the key form ([CODE-0], ten digits) are no key: the mail becomes a new issue")
    void bracketsThatAreNoKey(String form) {
        Scene s = scene();
        String subject = "Re: [" + String.format(Locale.ROOT, form, s.company().code()) + "] " + Unique.number();
        String content = "Body " + Unique.number();

        send(mailFrom(s.customer()).subject(subject).text(content));
        rounds.next();

        List<JsonNode> issues = issuesOf(s.customer());
        assertThat(issues).as("issues of the customer").extracting(i -> i.path("title").asString(""))
                .containsExactlyInAnyOrder(s.issue().json().path("title").asString(), subject);
        assertThat(comments(s.issue().key())).as("comments on the existing issue")
                .noneMatch(c -> content.equals(c.path("content").asString("")));
    }
}
