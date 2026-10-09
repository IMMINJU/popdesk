package io.popdesk.boundary;

import static io.popdesk.boundary.BoundaryChecks.allPages;
import static io.popdesk.boundary.BoundaryChecks.assertSameAsUnknownId;
import static io.popdesk.boundary.BoundaryChecks.attachmentPath;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.boundary.BoundaryChecks.Pages;
import io.popdesk.boundary.CompanyWorld.Side;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Attachment;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The customer-company boundary on every path of the slice (S0-AC-92), on one fixture of two
 * companies ({@link CompanyWorld}) made once for the class. A 404 for something out of scope is
 * compared with the 404 for something that does not exist; for attachments, status, headers but
 * {@code Date}, and body bytes (S0-AC-102). Lists are read page by page and searched as text for the
 * other company's keys, titles, and code.
 *
 * <p>Requests that would change data (filing, commenting, attaching, deleting) are all expected to
 * be refused; each test checks afterwards that nothing changed. If one is not refused, later tests
 * of this class may see the change.
 */
class CompanyBoundaryTest extends BareAppTest {

    private static final String NO_KEY = "ZZ9NOPE-999999";

    private static CompanyWorld world;

    private CompanyWorld w;

    @BeforeEach
    void setUp() {
        if (world == null) {
            world = CompanyWorld.build(fixtures);
        }
        w = world;
    }

    private static String issuePath(Session session, String key) {
        return session.api("/issues/" + key);
    }

    /** {@code caller}'s request answers like the same request for an issue key that does not exist: 404 NOT_FOUND, same body. */
    private static void assertNotFoundLikeNoIssue(SoftAssertions softly, String what, ApiResponse actual,
            ApiResponse unknown) {
        softly.assertThat(unknown.status()).as(what + " of an unknown issue: " + unknown.request()).isEqualTo(404);
        softly.assertThat(actual.status()).as(what + ": " + actual.request() + " -> " + actual.snippet())
                .isEqualTo(404);
        if (actual.status() == 404) {
            softly.assertThat(actual.errorCode()).as(what + " code").isEqualTo("NOT_FOUND");
            softly.assertThat(actual.body()).as(what + " body, like an unknown issue").isEqualTo(unknown.body());
        }
    }

    /** Detail, comment list, and attachment list of {@code key} answer {@code caller} like an issue that does not exist. */
    private static void assertIssueHidden(SoftAssertions softly, String who, Session caller, String key) {
        String path = issuePath(caller, key);
        String none = issuePath(caller, NO_KEY);
        assertNotFoundLikeNoIssue(softly, who + ", detail", caller.client().get(path).send(),
                caller.client().get(none).send());
        assertNotFoundLikeNoIssue(softly, who + ", comments", caller.client().get(path + "/comments").send(),
                caller.client().get(none + "/comments").send());
        assertNotFoundLikeNoIssue(softly, who + ", attachments", caller.client().get(path + "/attachments").send(),
                caller.client().get(none + "/attachments").send());
    }

    /** The owner still gets the file: a refused request did not delete it. */
    private static void assertStillThere(Session owner, String key, Attachment file) {
        ApiResponse download = owner.client().get(attachmentPath(owner, key, file.id())).expect(200);
        assertThat(download.body()).as("content of " + file.id() + " for its owner").isEqualTo(file.content());
    }

    private static List<String> commentTexts(Session session, String key) {
        return allPages(session, session.api("/issues/" + key + "/comments")).texts("content");
    }

    private static List<String> attachmentIds(Session session, String key) {
        return allPages(session, session.api("/issues/" + key + "/attachments")).texts("id");
    }

    // A customer of A and the issues of B

    @Test
    @DisplayName("S0-AC-92 a customer of A gets 404 for the detail, comments, and attachments of B's public and private issues, as for no issue")
    void customerOfASeesNoIssueOfB() {
        SoftAssertions softly = new SoftAssertions();
        for (Session caller : List.of(w.a().reporter(), w.a().colleague())) {
            assertIssueHidden(softly, "customer of A", caller, w.b().publicIssue().key());
            assertIssueHidden(softly, "customer of A", caller, w.b().privateIssue().key());
        }
        softly.assertAll();

        w.b().reporter().client().get(issuePath(w.b().reporter(), w.b().publicIssue().key())).expect(200);
    }

    @Test
    @DisplayName("S0-AC-92 a customer of A downloading B's attachments gets the unknown-ID 404 on every attachment endpoint, and the files stay")
    void customerOfAGetsNoAttachmentOfB() {
        Session caller = w.a().reporter();
        String own = w.a().publicIssue().key();
        Side b = w.b();
        SoftAssertions softly = new SoftAssertions();
        assertSameAsUnknownId(softly, "file on B's public issue", caller, own, b.publicIssue().key(),
                b.publicIssueFile().id());
        assertSameAsUnknownId(softly, "file on B's public comment", caller, own, b.publicIssue().key(),
                b.publicCommentFile().id());
        assertSameAsUnknownId(softly, "file on B's private issue", caller, own, b.privateIssue().key(),
                b.privateIssueFile().id());
        assertSameAsUnknownId(softly, "B's file under A's own issue", caller, own, own, b.publicIssueFile().id());
        softly.assertAll();

        assertStillThere(b.reporter(), b.publicIssue().key(), b.publicIssueFile());
        assertStillThere(b.reporter(), b.privateIssue().key(), b.privateIssueFile());
        assertStillThere(w.admin(), b.publicIssue().key(), b.publicCommentFile());
    }

    @Test
    @DisplayName("S0-AC-92 the issue list of a customer of A, on every page, has A's public issues and no key, title, or code of B")
    void customerIssueListHasNothingOfB() {
        Pages list = allPages(w.a().reporter(), "/api/portal/issues");
        Pages colleagues = allPages(w.a().colleague(), "/api/portal/issues");

        assertThat(list.texts("key")).as("keys the reporter of A lists")
                .contains(w.a().publicIssue().key(), w.a().privateIssue().key());
        assertThat(colleagues.texts("key")).as("keys the colleague of A lists").contains(w.a().publicIssue().key());
        for (Pages pages : List.of(list, colleagues)) {
            assertThat(pages.text()).as("issue list of a customer of A")
                    .doesNotContain(w.b().publicIssue().key())
                    .doesNotContain(w.b().privateIssue().key())
                    .doesNotContain(w.b().publicTitle())
                    .doesNotContain(w.b().privateTitle())
                    .doesNotContain(w.b().code());
        }
    }

    @Test
    @DisplayName("S0-AC-92 the company list of a customer of A has A and no code or name of B")
    void customerCompanyListHasNothingOfB() {
        Pages list = allPages(w.a().reporter(), "/api/portal/companies");

        assertThat(list.texts("code")).as("companies a customer of A lists").contains(w.a().code());
        assertThat(list.text()).as("company list of a customer of A")
                .doesNotContain(w.b().code())
                .doesNotContain(w.b().company().name());
    }

    @Test
    @DisplayName("S0-AC-92 a customer of A filing in B, as JSON or multipart with a file, gets 404 NOT_FOUND, as for a company that does not exist, and nothing is filed")
    void customerOfACannotFileInB() {
        String title = "Filed across #" + Unique.number() + "#";
        String withFile = "Filed across with a file #" + Unique.number() + "#";
        Session caller = w.a().reporter();

        ApiResponse intoB = caller.client().post("/api/portal/issues")
                .json(Map.of("companyCode", w.b().code(), "title", title, "content", "Across the boundary."))
                .send();
        ApiResponse intoNowhere = caller.client().post("/api/portal/issues")
                .json(Map.of("companyCode", "ZZ9NOPE", "title", title, "content", "Into nothing."))
                .send();
        ApiResponse multipartIntoB = fileWithAttachment(caller, w.b().code(), withFile);
        ApiResponse multipartIntoNowhere = fileWithAttachment(caller, "ZZ9NOPE", withFile);

        SoftAssertions softly = new SoftAssertions();
        assertNotFoundLikeNoIssue(softly, "filing in B", intoB, intoNowhere);
        assertNotFoundLikeNoIssue(softly, "filing in B with a file", multipartIntoB, multipartIntoNowhere);
        softly.assertAll();
        for (String filed : List.of(title, withFile)) {
            assertThat(allPages(w.b().reporter(), "/api/portal/issues").text()).as("issues B's customer sees")
                    .doesNotContain(filed);
            assertThat(allPages(caller, "/api/portal/issues").text()).as("issues A's customer sees")
                    .doesNotContain(filed);
            assertThat(allPages(w.admin(), "/api/console/issues").text()).as("issues the admin sees")
                    .doesNotContain(filed);
        }
    }

    /** {@code POST /api/portal/issues} as {@code multipart/form-data}: the issue part and one file. */
    private static ApiResponse fileWithAttachment(Session caller, String code, String title) {
        return caller.client().post("/api/portal/issues")
                .jsonPart("issue", Map.of("companyCode", code, "title", title, "content", "Across with a file."))
                .file("files", "across-" + Unique.number() + ".txt", "text/plain", "across".getBytes())
                .send();
    }

    @Test
    @DisplayName("S0-AC-92 a customer of A commenting on B's public and private issues gets 404 and no comment is added")
    void customerOfACannotCommentOnB() {
        Session caller = w.a().reporter();
        String text = "Comment across #" + Unique.number() + "#";
        SoftAssertions softly = new SoftAssertions();
        for (String key : List.of(w.b().publicIssue().key(), w.b().privateIssue().key())) {
            assertNotFoundLikeNoIssue(softly, "comment on " + key,
                    caller.client().post(issuePath(caller, key) + "/comments").json(Map.of("content", text)).send(),
                    caller.client().post(issuePath(caller, NO_KEY) + "/comments").json(Map.of("content", text))
                            .send());
        }
        softly.assertAll();

        assertThat(commentTexts(w.admin(), w.b().publicIssue().key())).doesNotContain(text);
        assertThat(commentTexts(w.admin(), w.b().privateIssue().key())).doesNotContain(text);
    }

    @Test
    @DisplayName("S0-AC-92 a customer of A attaching to B's issue or to B's comment gets 404 and no file is added")
    void customerOfACannotAttachToB() {
        Session caller = w.a().reporter();
        String name = "across-" + Unique.number() + ".txt";
        String key = w.b().publicIssue().key();
        List<String> before = attachmentIds(w.admin(), key);

        ApiResponse toIssue = caller.client().post(issuePath(caller, key) + "/attachments")
                .file("file", name, "text/plain", "across".getBytes()).send();
        ApiResponse toComment = caller.client().post(issuePath(caller, key) + "/attachments")
                .file("file", name, "text/plain", "across".getBytes())
                .part("commentId", Long.toString(w.b().publicComment().id())).send();
        ApiResponse toNoIssue = caller.client().post(issuePath(caller, NO_KEY) + "/attachments")
                .file("file", name, "text/plain", "across".getBytes()).send();

        SoftAssertions softly = new SoftAssertions();
        assertNotFoundLikeNoIssue(softly, "attach to B's issue", toIssue, toNoIssue);
        assertNotFoundLikeNoIssue(softly, "attach to B's comment", toComment, toNoIssue);
        softly.assertAll();
        assertThat(attachmentIds(w.admin(), key)).as("attachments of " + key).isEqualTo(before);
        assertThat(allPages(w.admin(), w.admin().api("/issues/" + key + "/attachments")).text()).doesNotContain(name);
    }

    // The agent assigned to A only

    @Test
    @DisplayName("S0-AC-92 the agent assigned to A only gets 404 for B's issues, their comments and attachments, and for company B")
    void agentOfASeesNoIssueOfB() {
        Session agent = w.agentOfA();
        SoftAssertions softly = new SoftAssertions();
        assertIssueHidden(softly, "agent of A", agent, w.b().publicIssue().key());
        assertIssueHidden(softly, "agent of A", agent, w.b().privateIssue().key());
        assertNotFoundLikeNoIssue(softly, "company B", agent.client().get("/api/console/companies/" + w.b().code())
                .send(), agent.client().get("/api/console/companies/ZZ9NOPE").send());
        softly.assertAll();

        agent.client().get(issuePath(agent, w.a().privateIssue().key())).expect(200);
    }

    @Test
    @DisplayName("S0-AC-92 the agent assigned to A only gets the unknown-ID 404 for B's attachments on every attachment endpoint, and the files stay")
    void agentOfAGetsNoAttachmentOfB() {
        Session agent = w.agentOfA();
        String own = w.a().publicIssue().key();
        Side b = w.b();
        SoftAssertions softly = new SoftAssertions();
        assertSameAsUnknownId(softly, "file on B's public issue", agent, own, b.publicIssue().key(),
                b.publicIssueFile().id());
        assertSameAsUnknownId(softly, "file on B's internal comment", agent, own, b.publicIssue().key(),
                b.internalCommentFile().id());
        assertSameAsUnknownId(softly, "file on B's private issue", agent, own, b.privateIssue().key(),
                b.privateIssueFile().id());
        softly.assertAll();

        assertStillThere(w.admin(), b.publicIssue().key(), b.internalCommentFile());
        assertStillThere(b.reporter(), b.privateIssue().key(), b.privateIssueFile());
    }

    @Test
    @DisplayName("S0-AC-92 the agent assigned to A only lists A's issues, private ones too, and no key, title, or code of B")
    void agentListHasNothingOfB() {
        Pages list = allPages(w.agentOfA(), "/api/console/issues");

        assertThat(list.texts("key")).contains(w.a().publicIssue().key(), w.a().privateIssue().key());
        assertThat(list.text()).as("issue list of the agent of A")
                .doesNotContain(w.b().publicIssue().key())
                .doesNotContain(w.b().privateIssue().key())
                .doesNotContain(w.b().publicTitle())
                .doesNotContain(w.b().privateTitle())
                .doesNotContain(w.b().code());
    }

    @Test
    @DisplayName("S0-AC-92 the agent assigned to A only has A and not B in the company list")
    void agentCompanyListHasNothingOfB() {
        Pages list = allPages(w.agentOfA(), "/api/console/companies");

        assertThat(list.texts("code")).contains(w.a().code());
        assertThat(list.text()).as("company list of the agent of A")
                .doesNotContain(w.b().code())
                .doesNotContain(w.b().company().name());
        // B has an assignment of its own, so a list of every assigned company would hold B.
        assertThat(allPages(w.agentOfB(), "/api/console/companies").texts("code")).as("companies the agent of B lists")
                .contains(w.b().code())
                .doesNotContain(w.a().code());
    }

    @Test
    @DisplayName("S0-AC-92 the agent assigned to A only commenting or attaching on B's issue gets 404 and nothing is added")
    void agentOfACannotWriteOnB() {
        Session agent = w.agentOfA();
        String key = w.b().publicIssue().key();
        String text = "Agent across #" + Unique.number() + "#";
        List<String> before = attachmentIds(w.admin(), key);

        SoftAssertions softly = new SoftAssertions();
        assertNotFoundLikeNoIssue(softly, "public comment on B",
                agent.client().post(issuePath(agent, key) + "/comments").json(Map.of("content", text)).send(),
                agent.client().post(issuePath(agent, NO_KEY) + "/comments").json(Map.of("content", text)).send());
        assertNotFoundLikeNoIssue(softly, "internal comment on B",
                agent.client().post(issuePath(agent, key) + "/comments")
                        .json(Map.of("content", text, "internal", true)).send(),
                agent.client().post(issuePath(agent, NO_KEY) + "/comments")
                        .json(Map.of("content", text, "internal", true)).send());
        assertNotFoundLikeNoIssue(softly, "attach to B",
                agent.client().post(issuePath(agent, key) + "/attachments")
                        .file("file", "agent.txt", "text/plain", "x".getBytes()).send(),
                agent.client().post(issuePath(agent, NO_KEY) + "/attachments")
                        .file("file", "agent.txt", "text/plain", "x".getBytes()).send());
        softly.assertAll();

        assertThat(commentTexts(w.admin(), key)).doesNotContain(text);
        assertThat(attachmentIds(w.admin(), key)).isEqualTo(before);
    }

    // Colleagues in the same company

    @Test
    @DisplayName("S0-AC-92 a colleague gets 404 for the reporter's private issue, its comments and attachments, and does not list it")
    void colleagueSeesNoPrivateIssue() {
        Session colleague = w.a().colleague();
        SoftAssertions softly = new SoftAssertions();
        assertIssueHidden(softly, "colleague", colleague, w.a().privateIssue().key());
        softly.assertAll();

        assertThat(allPages(colleague, "/api/portal/issues").text()).as("issue list of the colleague")
                .doesNotContain(w.a().privateIssue().key())
                .doesNotContain(w.a().privateTitle());
        colleague.client().get(issuePath(colleague, w.a().publicIssue().key())).expect(200);
    }

    @Test
    @DisplayName("S0-AC-92 a colleague commenting on the reporter's public issue gets 403 FORBIDDEN and no comment is added")
    void colleagueCannotCommentOnPublicIssue() {
        Session colleague = w.a().colleague();
        String key = w.a().publicIssue().key();
        String text = "Colleague comment #" + Unique.number() + "#";

        ApiResponse response = colleague.client().post(issuePath(colleague, key) + "/comments")
                .json(Map.of("content", text)).send();

        assertThat(response.status()).as(response.request() + " -> " + response.snippet()).isEqualTo(403);
        assertThat(response.errorCode()).isEqualTo("FORBIDDEN");
        assertThat(commentTexts(w.a().reporter(), key)).doesNotContain(text);
    }

    @Test
    @DisplayName("S0-AC-92 a colleague attaching to the reporter's public issue gets 403 FORBIDDEN and no file is added")
    void colleagueCannotAttachToPublicIssue() {
        Session colleague = w.a().colleague();
        String key = w.a().publicIssue().key();
        String name = "colleague-" + Unique.number() + ".txt";
        List<String> before = attachmentIds(w.a().reporter(), key);

        ApiResponse response = colleague.client().post(issuePath(colleague, key) + "/attachments")
                .file("file", name, "text/plain", "colleague".getBytes()).send();

        assertThat(response.status()).as(response.request() + " -> " + response.snippet()).isEqualTo(403);
        assertThat(response.errorCode()).isEqualTo("FORBIDDEN");
        assertThat(attachmentIds(w.a().reporter(), key)).isEqualTo(before);
    }

    @Test
    @DisplayName("S0-AC-92 a colleague downloading the reporter's private issue attachment gets the unknown-ID 404 on every attachment endpoint, and the file stays")
    void colleagueGetsNoPrivateAttachment() {
        Session colleague = w.a().colleague();
        SoftAssertions softly = new SoftAssertions();
        assertSameAsUnknownId(softly, "file on the reporter's private issue", colleague, w.a().publicIssue().key(),
                w.a().privateIssue().key(), w.a().privateIssueFile().id());
        softly.assertAll();

        assertStillThere(w.a().reporter(), w.a().privateIssue().key(), w.a().privateIssueFile());
    }

    // Internal comments and their files

    @Test
    @DisplayName("S0-AC-92 customers' comment lists leave out the internal comment; staff see it")
    void internalCommentNotListedForCustomers() {
        String key = w.a().publicIssue().key();
        String internal = w.a().internalText();

        for (Session customer : List.of(w.a().reporter(), w.a().colleague())) {
            Pages comments = allPages(customer, customer.api("/issues/" + key + "/comments"));
            assertThat(comments.texts("content")).as("comments a customer lists")
                    .contains(w.a().publicComment().json().path("content").asString(null));
            assertThat(comments.text()).as("comment list of a customer").doesNotContain(internal);
            assertThat(comments.items()).as("comment IDs a customer lists")
                    .noneMatch(n -> n.path("id").asLong(-1) == w.a().internalComment().id());
        }
        assertThat(commentTexts(w.agentOfA(), key)).as("comments the agent lists").contains(internal);
    }

    @Test
    @DisplayName("S0-AC-92 customers' attachment lists leave out the internal comment's file; staff see it")
    void internalAttachmentNotListedForCustomers() {
        String key = w.a().publicIssue().key();
        Attachment internal = w.a().internalCommentFile();

        for (Session customer : List.of(w.a().reporter(), w.a().colleague())) {
            Pages files = allPages(customer, customer.api("/issues/" + key + "/attachments"));
            assertThat(files.texts("id")).as("attachments a customer lists")
                    .contains(w.a().publicIssueFile().id(), w.a().publicCommentFile().id());
            assertThat(files.text()).as("attachment list of a customer")
                    .doesNotContain(internal.id())
                    .doesNotContain(internal.json().path("fileName").asString("internal-comment-"));
        }
        assertThat(attachmentIds(w.agentOfA(), key)).as("attachments the agent lists").contains(internal.id());
    }

    @Test
    @DisplayName("S0-AC-92 customers asking for the internal comment's file get the unknown-ID 404 on every attachment endpoint, and it stays")
    void internalAttachmentIsNotFoundForCustomers() {
        String key = w.a().publicIssue().key();
        SoftAssertions softly = new SoftAssertions();
        for (Session customer : List.of(w.a().reporter(), w.a().colleague())) {
            assertSameAsUnknownId(softly, "internal file", customer, key, key, w.a().internalCommentFile().id());
        }
        softly.assertAll();

        assertStillThere(w.agentOfA(), key, w.a().internalCommentFile());
    }
}
