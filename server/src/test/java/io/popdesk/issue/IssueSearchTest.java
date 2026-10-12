package io.popdesk.issue;

import static io.popdesk.issue.IssueFlow.list;
import static io.popdesk.issue.IssueFlow.listRequest;
import static io.popdesk.issue.IssueFlow.total;
import static io.popdesk.issue.IssueSteps.fieldCode;
import static io.popdesk.issue.IssueSteps.fieldNames;
import static io.popdesk.issue.IssueSteps.keys;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow.World;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.App;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.NewIssue;
import io.popdesk.support.Fixtures.Session;
import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The search word {@code q} of the two issue lists: what text it finds, its limits, and its index.
 * Every search here is by a customer of the test's own company or by an agent assigned to that
 * company alone, so a list holds only issues the test filed.
 */
class IssueSearchTest extends BareAppTest {

    /** 한 in decomposed form: three code points that NFC joins into one. */
    private static final String HAN_NFD = Normalizer.normalize("한", Normalizer.Form.NFD);

    /** One character outside the basic plane: one code point, two UTF-16 code units. */
    private static final String EMOJI = "😀";

    private IssueFlow flow;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
    }

    private static Session viewer(World w, App app) {
        return app == App.PORTAL ? w.reporter() : w.agent();
    }

    // S3-AC-11

    @ParameterizedTest(name = "{0}")
    @EnumSource(App.class)
    @DisplayName("S3-AC-11, S3-AC-14 q finds an issue by any part of its title or content, ignoring case and the spaces around q, and goes together with other filters")
    void findsByTitleOrContent(App app) {
        World w = flow.world();
        Session viewer = viewer(w, app);
        String inTitle = IssueFlow.word();
        String inContent = IssueFlow.word();
        String korean = IssueFlow.koreanWord();
        Issue target = fixtures.fileIssue(w.reporter(), NewIssue.in(w.code())
                .title("Login " + inTitle + " fails")
                .content("It says " + inContent + " twice.\n화면에 " + korean + " 메시지가 뜹니다."));
        fixtures.fileIssue(w.reporter(), w.code());
        Map<String, String> needles = new LinkedHashMap<>();
        needles.put("a word of the title", inTitle);
        needles.put("a word of the content", inContent);
        needles.put("the title word in capitals", inTitle.toUpperCase(Locale.ROOT));
        needles.put("the middle of the title word", inTitle.substring(1, inTitle.length() - 1));
        needles.put("the end of one word and the start of the next", "in " + inTitle.substring(0, 3));
        needles.put("the title word with two spaces before and after it", "  " + inTitle + "  ");
        needles.put("a Korean word of the content", korean);
        needles.put("the middle of the Korean word", korean.substring(1, korean.length() - 1));

        SoftAssertions softly = new SoftAssertions();
        needles.forEach((what, needle) -> {
            ApiResponse page = list(viewer, "q", needle);
            softly.assertThat(keys(page)).as("issues found by %s (%s)", what, needle).containsExactly(target.key());
            softly.assertThat(total(page)).as("totalElements for %s (%s)", what, needle).isEqualTo(1);
        });
        softly.assertThat(keys(list(viewer, "q", IssueFlow.word()))).as("issues found by a word no issue has")
                .isEmpty();
        softly.assertThat(keys(list(viewer, "q", inTitle, "status", "RECEIVED", "companyCode", w.code())))
                .as("issues found by the title word among RECEIVED ones").containsExactly(target.key());
        softly.assertThat(keys(list(viewer, "q", inTitle, "status", "CLOSED")))
                .as("issues found by the title word among CLOSED ones").isEmpty();
        softly.assertAll();
    }

    // S3-AC-12

    @Test
    @DisplayName("S3-AC-12 q searches the content as it is shown: text of bold, links, images, and code, but no Markdown marks, link or image addresses, or fence language")
    void searchesTheShownText() {
        World w = flow.world();
        Map<String, String> shown = new LinkedHashMap<>();
        for (String part : List.of("heading", "bold", "link text", "list item", "quote", "image description", "code",
                "address written as text", "HTML attribute", "text inside HTML")) {
            shown.put(part, IssueFlow.word());
        }
        String linkHost = IssueFlow.word();
        String linkPath = IssueFlow.word();
        String language = IssueFlow.word();
        String content = String.join("\n",
                "# Heading " + shown.get("heading"),
                "",
                "Some **" + shown.get("bold") + "** text and [" + shown.get("link text") + "](https://" + linkHost
                        + ".example/" + linkPath + ").",
                "",
                "- item " + shown.get("list item"),
                "",
                "> quote " + shown.get("quote"),
                "",
                "![" + shown.get("image description") + "](attachment:1234)",
                "",
                "```" + language,
                shown.get("code") + " = 1",
                "```",
                "",
                "See <https://" + shown.get("address written as text") + ".example/page>.",
                "",
                "<div class=\"" + shown.get("HTML attribute") + "\">" + shown.get("text inside HTML") + "</div>");
        Issue target = fixtures.fileIssue(w.reporter(), NewIssue.in(w.code()).title("Markdown report").content(content));
        fixtures.fileIssue(w.reporter(), w.code());
        Map<String, String> notShown = new LinkedHashMap<>();
        notShown.put("the host of the link address", linkHost);
        notShown.put("the path of the link address", linkPath);
        notShown.put("the scheme of the image address", "attachment");
        notShown.put("the language of the code fence", language);
        notShown.put("the bold mark", "**");
        notShown.put("the bold mark with its text", "**" + shown.get("bold"));
        notShown.put("the heading mark", "# Heading");
        notShown.put("the list mark", "- item");
        notShown.put("the quote mark", "> quote");
        notShown.put("the code fence", "```");
        notShown.put("the link brackets", "](");

        SoftAssertions softly = new SoftAssertions();
        shown.forEach((what, needle) -> softly.assertThat(keys(list(w.agent(), "q", needle)))
                .as("issues found by the %s (%s)", what, needle).containsExactly(target.key()));
        notShown.forEach((what, needle) -> softly.assertThat(keys(list(w.agent(), "q", needle)))
                .as("issues found by %s (%s)", what, needle).isEmpty());
        softly.assertAll();
    }

    // S3-AC-13

    @ParameterizedTest(name = "q={0}")
    @ValueSource(strings = {"100%", "a_b", "c\\d", "%", "_", "\\"})
    @DisplayName("S3-AC-13 %, _, and \\ in q are ordinary characters: they find only issues that hold them")
    void wildcardsAreOrdinaryCharacters(String needle) {
        World w = flow.world();
        String tail = " " + IssueFlow.word();
        Issue target = fixtures.fileIssue(w.reporter(), NewIssue.in(w.code()).title("Rate 100% a_b c\\d" + tail));
        Issue decoy = fixtures.fileIssue(w.reporter(), NewIssue.in(w.code()).title("Rate 100x axb cd" + tail));

        ApiResponse page = list(w.agent(), "q", needle);

        assertThat(keys(page)).as("issues found by %s", needle).containsExactly(target.key())
                .doesNotContain(decoy.key());
        assertThat(total(page)).as("totalElements").isEqualTo(1);
    }

    // S3-AC-14

    @ParameterizedTest(name = "{0}")
    @EnumSource(App.class)
    @DisplayName("S3-AC-14 q is 1 to 100 characters, counted in code points after NFC: empty, blank, and 101 are 400 with field q")
    void searchTextLimits(App app) {
        World w = flow.world();
        Session viewer = viewer(w, app);
        fixtures.fileIssue(w.reporter(), w.code());
        Map<String, String> refused = new LinkedHashMap<>();
        refused.put("an empty q", "");
        refused.put("a q of spaces", "   ");
        refused.put("101 letters", "a".repeat(101));
        refused.put("101 decomposed Hangul syllables", HAN_NFD.repeat(101));
        refused.put("101 characters outside the basic plane", EMOJI.repeat(101));
        Map<String, String> accepted = new LinkedHashMap<>();
        accepted.put("100 letters", "a".repeat(100));
        accepted.put("100 decomposed Hangul syllables", HAN_NFD.repeat(100));
        accepted.put("100 characters outside the basic plane", EMOJI.repeat(100));

        SoftAssertions softly = new SoftAssertions();
        refused.forEach((what, q) -> {
            ApiResponse rejected = listRequest(viewer, "q", q).send();
            softly.assertThat(rejected.status()).as("status for %s", what).isEqualTo(400);
            softly.assertThat(rejected.json().path("code").asString(null)).as("code for %s", what)
                    .isEqualTo("INVALID_INPUT");
            softly.assertThat(rejected.json().path("fields").isArray() ? fieldNames(rejected) : List.of())
                    .as("fields for %s", what).contains("q");
        });
        softly.assertThat(fieldCode(listRequest(viewer, "q", "").send(), "q")).as("field code for an empty q")
                .isEqualTo("TOO_SHORT");
        softly.assertThat(fieldCode(listRequest(viewer, "q", "a".repeat(101)).send(), "q"))
                .as("field code for 101 letters").isEqualTo("TOO_LONG");
        accepted.forEach((what, q) -> {
            ApiResponse page = listRequest(viewer, "q", q).send();
            softly.assertThat(page.status()).as("status for %s", what).isEqualTo(200);
            softly.assertThat(page.json().path("totalElements").asLong(-1)).as("totalElements for %s", what).isZero();
        });
        softly.assertAll();
    }

    // S3-AC-16

    @Test
    @DisplayName("S3-AC-16 after an edit the search finds the new title and content, and no longer the old ones")
    void editedTextIsSearched() {
        World w = flow.world();
        String oldTitle = IssueFlow.word();
        String oldContent = IssueFlow.word();
        String newTitle = IssueFlow.word();
        String newContent = IssueFlow.word();
        Issue issue = fixtures.fileIssue(w.reporter(), NewIssue.in(w.code()).title("Before " + oldTitle)
                .content("It was **" + oldContent + "**."));

        flow.edit(w.reporter(), issue.key(),
                Map.of("title", "After " + newTitle, "content", "It is **" + newContent + "** now."));

        assertThat(keys(list(w.agent(), "q", newTitle))).as("issues found by the new title").containsExactly(issue.key());
        assertThat(keys(list(w.agent(), "q", newContent))).as("issues found by the new content")
                .containsExactly(issue.key());
        assertThat(keys(list(w.agent(), "q", oldTitle))).as("issues found by the old title").isEmpty();
        assertThat(keys(list(w.agent(), "q", oldContent))).as("issues found by the old content").isEmpty();
        assertThat(keys(list(w.reporter(), "q", newTitle))).as("issues the customer finds by the new title")
                .containsExactly(issue.key());
    }

    // S3-AC-17

    /** Definitions of the indexes of {@code table} that use the trigram operator class. */
    private List<String> trigramIndexes(String table) {
        assertThat(db.tables()).as("tables of the app").contains(table);
        return db.indexes(table).values().stream().filter(definition -> definition.contains("gin_trgm_ops")).toList();
    }

    @Test
    @DisplayName("S3-AC-17 pg_trgm is installed, the issue table has one trigram index, and the account table has them on name and email")
    void trigramIndexes() {
        assertThat(db.count("select count(*) from pg_extension where extname = 'pg_trgm'")).as("pg_trgm extensions")
                .isEqualTo(1);

        List<String> ofIssue = trigramIndexes("issue");
        List<String> ofAccount = trigramIndexes("account");

        assertThat(ofIssue).as("trigram indexes of issue").hasSize(1)
                .allMatch(definition -> definition.toLowerCase(Locale.ROOT).contains("using gin"));
        assertThat(ofAccount).as("trigram indexes of account")
                .anyMatch(definition -> definition.matches("(?is).*\\bname\\b[^,]*gin_trgm_ops.*"))
                .anyMatch(definition -> definition.matches("(?is).*\\bemail\\b[^,]*gin_trgm_ops.*"));
    }
}
