package io.popdesk.issue;

import static io.popdesk.issue.IssueSteps.fieldCode;
import static io.popdesk.issue.IssueSteps.text;
import static io.popdesk.issue.TalkFlow.accountIds;
import static io.popdesk.issue.TalkFlow.body;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow.World;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Comment;
import io.popdesk.support.Fixtures.Invitation;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/**
 * Mentions: staff name accounts with a comment, when adding it and when editing it; who can be
 * named, how many, and that the portal neither takes nor shows them.
 */
class MentionTest extends BareAppTest {

    private IssueFlow flow;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
    }

    /** Account IDs of {@code n} new active agents assigned to the world's company. */
    private List<Long> assignedAgents(World w, int n) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ids.add(fixtures.agent(w.admin(), w.code()).accountId());
        }
        return ids;
    }

    /** Fails unless {@code mentions} holds exactly these people, each with account ID, name, and role. */
    private static void assertMentions(String what, JsonNode mentions, Session... people) {
        assertThat(accountIds(mentions)).as("accounts in %s", what)
                .containsExactlyInAnyOrderElementsOf(List.of(people).stream().map(Session::accountId).toList());
        for (Session person : people) {
            JsonNode given = TalkFlow.person(mentions, person.accountId());
            assertThat(text(given, "name")).as("name of %s in %s", person.accountId(), what).isEqualTo(person.name());
            assertThat(text(given, "role")).as("role of %s in %s", person.accountId(), what)
                    .isEqualTo(person.role().name());
        }
    }

    private static void assertRefused(ApiResponse response, String fieldCode) {
        assertThat(response.status()).as(response.request() + ": " + response.snippet()).isEqualTo(400);
        assertThat(response.errorCode()).as("code of %s", response.request()).isEqualTo("INVALID_INPUT");
        assertThat(fieldCode(response, "mentions")).as("mentions in %s", response.snippet()).isEqualTo(fieldCode);
    }

    // S4-AC-19

    @ParameterizedTest(name = "internal: {0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("S4-AC-19 an agent mentions an admin and the reporter in a public comment, an admin and another assigned agent in an internal one: the response and the console list give them with account ID, name, and role")
    void mentionsAreKeptWithTheComment(boolean internal) {
        World w = flow.world();
        Session otherAgent = fixtures.agent(w.admin(), w.code());
        Session second = internal ? otherAgent : w.reporter();
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());

        Comment added = TalkFlow.mention(w.agent(), issue.key(), internal,
                List.of(w.admin().accountId(), second.accountId()));

        assertMentions("the response", added.json().path("mentions"), w.admin(), second);
        JsonNode listed = TalkFlow.comment(otherAgent, issue.key(), added.id());
        assertMentions("the console list", listed.path("mentions"), w.admin(), second);
        assertThat(listed.path("internal").asBoolean(!internal)).isEqualTo(internal);
    }

    @Test
    @DisplayName("S4-AC-19 an account given twice is mentioned once")
    void sameAccountTwiceCountsOnce() {
        World w = flow.world();
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());

        Comment added = TalkFlow.mention(w.agent(), issue.key(), false,
                List.of(w.admin().accountId(), w.reporter().accountId(), w.admin().accountId()));

        assertMentions("the response", added.json().path("mentions"), w.admin(), w.reporter());
        assertMentions("the console list", TalkFlow.comment(w.admin(), issue.key(), added.id()).path("mentions"),
                w.admin(), w.reporter());
    }

    @Test
    @DisplayName("S4-AC-19 ten accounts can be mentioned in one comment")
    void tenAccountsAreMentioned() {
        World w = flow.world();
        List<Long> ten = new ArrayList<>(assignedAgents(w, 9));
        ten.add(w.admin().accountId());
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());

        Comment added = TalkFlow.mention(w.agent(), issue.key(), true, ten);

        assertThat(accountIds(added.json().path("mentions"))).containsExactlyInAnyOrderElementsOf(ten);
        assertThat(accountIds(TalkFlow.comment(w.admin(), issue.key(), added.id()).path("mentions")))
                .containsExactlyInAnyOrderElementsOf(ten);
    }

    // S4-AC-20

    /** One refused mention: the kind of comment and the account. */
    private record Mention(boolean internal, long accountId) {}

    @Test
    @DisplayName("S4-AC-20 the author, the reporter in an internal comment, a colleague, a customer of another company, an agent who is not assigned, a suspended or invited agent, and an ID of nobody get the same 400 with mentions UNKNOWN_VALUE, alone or next to people who can be mentioned, and no comment is added")
    void accountsThatCannotBeMentionedGetTheSame400() {
        World w = flow.world();
        Session outsider = fixtures.customer(w.admin(), w.elsewhere().code());
        Invitation invited = fixtures.invite(w.admin(), Role.AGENT);
        fixtures.assignAgent(w.admin(), w.code(), invited.accountId());
        Session suspended = fixtures.agent(w.admin(), w.code());
        flow.suspend(w.admin(), suspended.accountId());
        Session otherAgent = fixtures.agent(w.admin(), w.code());
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());
        Map<String, Mention> mentions = new LinkedHashMap<>();
        mentions.put("the author", new Mention(false, w.agent().accountId()));
        mentions.put("the reporter, in an internal comment", new Mention(true, w.reporter().accountId()));
        mentions.put("a colleague of the reporter", new Mention(false, w.colleague().accountId()));
        mentions.put("a customer of another company", new Mention(false, outsider.accountId()));
        mentions.put("an agent assigned to no company", new Mention(false, w.unassignedAgent().accountId()));
        mentions.put("an agent assigned to another company only", new Mention(true, w.agentElsewhere().accountId()));
        mentions.put("a suspended agent of the company", new Mention(true, suspended.accountId()));
        mentions.put("an invited agent of the company", new Mention(false, invited.accountId()));
        mentions.put("an ID of nobody", new Mention(false, IssueFlow.NO_ACCOUNT));

        Map<String, ApiResponse> answers = new LinkedHashMap<>();
        mentions.forEach((who, m) -> answers.put(who, TalkFlow.addRequest(w.agent(), issue.key(),
                body("content", "Refused", "internal", m.internal(), "mentions", List.of(m.accountId()))).send()));
        ApiResponse mixed = TalkFlow.addRequest(w.agent(), issue.key(), body("content", "Refused", "mentions",
                List.of(otherAgent.accountId(), w.colleague().accountId(), w.admin().accountId()))).send();

        ApiResponse first = answers.values().iterator().next();
        answers.forEach((who, answer) -> {
            assertRefused(answer, "UNKNOWN_VALUE");
            assertThat(answer.text()).as("body for %s, like for the author", who).isEqualTo(first.text());
        });
        assertRefused(mixed, "UNKNOWN_VALUE");
        assertThat(TalkFlow.comments(w.admin(), issue.key())).as("comments after the refused mentions").isEmpty();
    }

    // S4-AC-21

    @Test
    @DisplayName("S4-AC-21 eleven accounts that could each be mentioned are 400 with mentions TOO_MANY, and no comment is added")
    void elevenMentionsAreRefused() {
        World w = flow.world();
        List<Long> eleven = assignedAgents(w, 11);
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());

        ApiResponse refused = TalkFlow.addRequest(w.agent(), issue.key(),
                body("content", "Too many", "internal", true, "mentions", eleven)).send();

        assertRefused(refused, "TOO_MANY");
        assertThat(TalkFlow.comments(w.admin(), issue.key())).as("comments after the refused mentions").isEmpty();
    }

    // S4-AC-24

    @Test
    @DisplayName("S4-AC-24, S4-AC-06 the author replaces the mentions [admin, agent] by [agent, reporter]: the response and the list give the new two, and editedAt stays null")
    void editReplacesTheMentions() {
        World w = flow.world();
        Session otherAgent = fixtures.agent(w.admin(), w.code());
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());
        Comment added = TalkFlow.mention(w.agent(), issue.key(), false,
                List.of(w.admin().accountId(), otherAgent.accountId()));
        assertMentions("the new comment", added.json().path("mentions"), w.admin(), otherAgent);
        flow.tick();

        JsonNode edited = TalkFlow.editMentions(w.agent(), issue.key(), added.id(),
                List.of(otherAgent.accountId(), w.reporter().accountId()));

        assertMentions("the response of the edit", edited.path("mentions"), otherAgent, w.reporter());
        JsonNode listed = TalkFlow.comment(w.admin(), issue.key(), added.id());
        assertMentions("the console list", listed.path("mentions"), otherAgent, w.reporter());
        assertThat(text(listed, "content")).isEqualTo(text(added.json(), "content"));
        assertThat(IssueFlow.time(listed, "editedAt")).as("editedAt after only the mentions changed").isNull();
    }

    @Test
    @DisplayName("S4-AC-24 an edit of the content alone leaves the mentions as they are")
    void editWithoutMentionsKeepsThem() {
        World w = flow.world();
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());
        Comment added = TalkFlow.mention(w.agent(), issue.key(), false,
                List.of(w.admin().accountId(), w.reporter().accountId()));
        String next = Unique.text("New content");

        JsonNode edited = TalkFlow.editContent(w.agent(), issue.key(), added.id(), next);

        assertThat(text(edited, "content")).isEqualTo(next);
        assertMentions("the response of the edit", edited.path("mentions"), w.admin(), w.reporter());
        assertMentions("the console list", TalkFlow.comment(w.admin(), issue.key(), added.id()).path("mentions"),
                w.admin(), w.reporter());
    }

    @ParameterizedTest(name = "mentions null: {0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("S4-AC-24 an edit with mentions as an empty list or as null leaves the comment without mentions")
    void emptyListOrNullClearsTheMentions(boolean asNull) {
        World w = flow.world();
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());
        Comment added = TalkFlow.mention(w.agent(), issue.key(), false,
                List.of(w.admin().accountId(), w.reporter().accountId()));
        assertMentions("the new comment", added.json().path("mentions"), w.admin(), w.reporter());

        JsonNode edited = TalkFlow.edit(w.agent(), issue.key(), added.id(),
                body("mentions", asNull ? null : List.of()));

        assertMentions("the response of the edit", edited.path("mentions"));
        JsonNode listed = TalkFlow.comment(w.admin(), issue.key(), added.id());
        assertMentions("the console list", listed.path("mentions"));
        assertThat(text(listed, "content")).isEqualTo(text(added.json(), "content"));
    }

    @Test
    @DisplayName("S4-AC-24 an edit checks the mentions as adding does: an account that cannot be mentioned is 400 UNKNOWN_VALUE, eleven accounts 400 TOO_MANY, and the comment stays")
    void editChecksTheMentions() {
        World w = flow.world();
        List<Long> eleven = assignedAgents(w, 11);
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());
        Comment added = TalkFlow.mention(w.agent(), issue.key(), false, List.of(w.admin().accountId()));
        // A list the edit takes, so the refusals below are about the accounts and their number.
        TalkFlow.editMentions(w.agent(), issue.key(), added.id(), List.of(w.admin().accountId(), eleven.getFirst()));
        JsonNode before = TalkFlow.comment(w.admin(), issue.key(), added.id());

        ApiResponse unknown = TalkFlow.editRequest(w.agent(), issue.key(), added.id(),
                body("mentions", List.of(w.admin().accountId(), w.colleague().accountId()))).send();
        ApiResponse tooMany = TalkFlow.editRequest(w.agent(), issue.key(), added.id(), body("mentions", eleven))
                .send();

        assertRefused(unknown, "UNKNOWN_VALUE");
        assertRefused(tooMany, "TOO_MANY");
        assertThat(TalkFlow.comment(w.admin(), issue.key(), added.id())).as("the comment after the refused edits")
                .isEqualTo(before);
    }

    @Test
    @DisplayName("S4-AC-24, S4-AC-02 someone who did not write the comment gets 403 FORBIDDEN on changing its mentions alone, and they stay")
    void onlyTheAuthorChangesTheMentions() {
        World w = flow.world();
        Session otherAgent = fixtures.agent(w.admin(), w.code());
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());
        Comment added = TalkFlow.mention(w.agent(), issue.key(), false, List.of(w.admin().accountId()));
        JsonNode before = TalkFlow.comment(w.admin(), issue.key(), added.id());
        assertMentions("the new comment", before.path("mentions"), w.admin());

        List<ApiResponse> refused = List.of(
                TalkFlow.editRequest(otherAgent, issue.key(), added.id(),
                        body("mentions", List.of(w.reporter().accountId()))).send(),
                TalkFlow.editRequest(w.admin(), issue.key(), added.id(), body("mentions", List.of())).send());

        for (ApiResponse answer : refused) {
            assertThat(answer.status()).as(answer.request() + ": " + answer.snippet()).isEqualTo(403);
            assertThat(answer.errorCode()).isEqualTo("FORBIDDEN");
        }
        assertThat(TalkFlow.comment(w.admin(), issue.key(), added.id())).as("the comment after the refused edits")
                .isEqualTo(before);
    }

    // S4-AC-25

    @Test
    @DisplayName("S4-AC-25 mentions in the body of a portal comment are ignored, when adding and when editing: the comment is kept and the console shows it without mentions")
    void portalIgnoresMentions() {
        World w = flow.world();
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());
        List<Long> staff = List.of(w.agent().accountId(), w.admin().accountId());
        String next = Unique.text("Edited on the portal");

        Comment added = TalkFlow.add(w.reporter(), issue.key(),
                body("content", Unique.text("Added on the portal"), "mentions", staff));
        JsonNode afterAdding = TalkFlow.comment(w.agent(), issue.key(), added.id());
        JsonNode edited = TalkFlow.edit(w.reporter(), issue.key(), added.id(),
                body("content", next, "mentions", staff));

        assertThat(accountIds(afterAdding.path("mentions"))).as("mentions after adding").isEmpty();
        assertThat(text(edited, "content")).isEqualTo(next);
        JsonNode afterEditing = TalkFlow.comment(w.agent(), issue.key(), added.id());
        assertThat(text(afterEditing, "content")).isEqualTo(next);
        assertThat(accountIds(afterEditing.path("mentions"))).as("mentions after editing").isEmpty();
    }

    @Test
    @DisplayName("S4-AC-25 a public comment that mentions the reporter has the mention on the console and no mentions field on the portal, in the list and in the response of the reporter's own edit")
    void portalCommentsCarryNoMentions() {
        World w = flow.world();
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());
        Comment answer = TalkFlow.mention(w.agent(), issue.key(), false,
                List.of(w.reporter().accountId(), w.admin().accountId()));
        Comment own = fixtures.comment(w.reporter(), issue.key(), Unique.text("By the reporter"));

        assertMentions("the console list", TalkFlow.comment(w.admin(), issue.key(), answer.id()).path("mentions"),
                w.reporter(), w.admin());
        JsonNode ownEdit = TalkFlow.editContent(w.reporter(), issue.key(), own.id(), Unique.text("Edited"));
        ApiResponse portal = TalkFlow.commentPage(w.reporter(), issue.key());
        assertThat(IssueSteps.items(portal)).hasSize(2)
                .allSatisfy(c -> assertThat(c.has("mentions")).as("the field mentions in %s", c).isFalse());
        assertThat(ownEdit.has("mentions")).as("the field mentions in %s", ownEdit).isFalse();
        assertThat(portal.text()).doesNotContainIgnoringCase(w.admin().email());
    }
}
