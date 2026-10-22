package io.popdesk.issue;

import static io.popdesk.issue.IssueFlow.list;
import static io.popdesk.issue.IssueFlow.listRequest;
import static io.popdesk.issue.IssueFlow.total;
import static io.popdesk.issue.IssueSteps.keys;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow.World;
import io.popdesk.label.LabelSteps;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.App;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.NewIssue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.trash.TrashSteps;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A search word {@code q} of one or two characters (slice b8): the same issues as before the slice,
 * which searched every length with {@code ilike} on the title, a line break, and the content as it
 * is shown. The expected issues are worked out here the same way, on texts without Markdown, so the
 * shown content is the content as written. Every search is by a customer of the test's own company or
 * by an agent assigned to that company alone, so a list holds only issues the test filed.
 */
class ShortSearchTest extends BareAppTest {

    /** Stands for the code of the other company in {@link #customerFilters()}. */
    private static final String OTHER_CODE = "@other";

    private IssueFlow flow;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
    }

    private static Session viewer(World w, App app) {
        return app == App.PORTAL ? w.reporter() : w.agent();
    }

    /** One issue the test filed, with the texts it was filed with. */
    private record Filed(Issue issue, String title, String content) {

        /** Whether the issue holds {@code q} as the search did before slice b8: NFC, spaces cut, any case. */
        boolean holds(String q) {
            String needle = Normalizer.normalize(q, Normalizer.Form.NFC).strip().toLowerCase(Locale.ROOT);
            return (title + "\n" + content).toLowerCase(Locale.ROOT).contains(needle);
        }
    }

    private Filed file(Session customer, String code, String title, String content) {
        return new Filed(fixtures.fileIssue(customer, NewIssue.in(code).title(title).content(content)), title,
                content);
    }

    private static List<String> holding(List<Filed> filed, String q) {
        return filed.stream().filter(f -> f.holds(q)).map(f -> f.issue().key()).toList();
    }

    // B8-AC-01

    @ParameterizedTest(name = "{0}")
    @EnumSource(App.class)
    @DisplayName("B8-AC-01 q of one or two characters finds the issues whose title or content holds it, ignoring case, "
            + "with %, _, and \\ as ordinary characters, counted in code points after NFC, as three characters do")
    void oneOrTwoCharactersFindWhatTheyDidBefore(App app) {
        World w = flow.world();
        Session viewer = viewer(w, app);
        List<Filed> filed = List.of(
                file(w.reporter(), w.code(), "Rate 100% a_b c\\d", "Printer jams"),
                file(w.reporter(), w.code(), "Rate 100x axb cd", "Plain words"),
                file(w.reporter(), w.code(), "결제 오류", "환불 요청😀끝"),
                file(w.reporter(), w.code(), "Ok", "Xy"),
                file(w.reporter(), w.code(), "QQ", "zz"),
                file(w.reporter(), w.code(), "Éclair Жалоба", "Ñandú"));
        Map<String, String> needles = new LinkedHashMap<>();
        needles.put("a letter", "x");
        needles.put("a letter in capitals", "X");
        needles.put("two letters in mixed case", "qQ");
        needles.put("two letters of the content in capitals", "XY");
        needles.put("%", "%");
        needles.put("_", "_");
        needles.put("a backslash", "\\");
        needles.put("a digit and %", "0%");
        needles.put("_ and a letter", "_b");
        needles.put("a backslash and a letter", "\\d");
        needles.put("a Hangul syllable", "결");
        needles.put("two Hangul syllables of the title", "결제");
        needles.put("two Hangul syllables of the content", "환불");
        needles.put("two decomposed Hangul syllables", Normalizer.normalize("결제", Normalizer.Form.NFD));
        needles.put("two Hangul syllables with spaces around them", "  환불  ");
        needles.put("a character outside the basic plane", "😀");
        needles.put("a Hangul syllable and a character outside the basic plane", "청😀");
        needles.put("a character outside the basic plane and a Hangul syllable", "😀끝");
        needles.put("the end of the title and the start of the content", "kx");
        needles.put("a Latin letter with an accent, small", "é");
        needles.put("a Latin letter with an accent, in capitals", "É");
        needles.put("two Cyrillic letters, small", "жа");
        needles.put("two letters with an accent of the content in capitals", "ÑA");
        needles.put("two letters no issue has", "jq");
        needles.put("three characters", "100");
        needles.put("three characters with _", "a_b");

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(holding(filed, "x")).as("issues the rule finds with x").hasSize(2);
        softly.assertThat(holding(filed, "%")).as("issues the rule finds with %").hasSize(1);
        softly.assertThat(holding(filed, "kx")).as("issues the rule finds with kx").isEmpty();
        softly.assertThat(holding(filed, "청😀")).as("issues the rule finds with 청😀").hasSize(1);
        softly.assertThat(holding(filed, "é")).as("issues the rule finds with é").hasSize(1);
        softly.assertThat(holding(filed, "жа")).as("issues the rule finds with жа").hasSize(1);
        needles.forEach((what, q) -> {
            List<String> expected = holding(filed, q);
            ApiResponse page = list(viewer, "q", q);
            softly.assertThat(keys(page)).as("issues found by %s (%s)", what, q)
                    .containsExactlyInAnyOrderElementsOf(expected);
            softly.assertThat(total(page)).as("totalElements for %s (%s)", what, q).isEqualTo(expected.size());
        });
        softly.assertAll();
    }

    // B8-AC-02

    @Test
    @DisplayName("B8-AC-02 the issue table has one gram index issue_search_grams_idx, a GIN index with collate \"C\", "
            + "next to its one trigram index")
    void gramIndex() {
        assertThat(db.tables()).as("tables of the app").contains("issue");
        Map<String, String> indexes = db.indexes("issue");

        assertThat(indexes).as("indexes of issue").containsKey("issue_search_grams_idx");
        String grams = indexes.get("issue_search_grams_idx").toLowerCase(Locale.ROOT);
        assertThat(grams).as("definition of issue_search_grams_idx").contains("using gin").contains("collate \"c\"")
                .doesNotContain("gin_trgm_ops");
        assertThat(indexes.values().stream().filter(definition -> definition.contains("gin_trgm_ops")).toList())
                .as("trigram indexes of issue").hasSize(1);
        assertThat(indexes.keySet().stream().filter(name -> name.contains("grams")).toList())
                .as("gram indexes of issue").containsExactly("issue_search_grams_idx");
    }

    // B8-AC-03

    static Stream<List<String>> customerFilters() {
        return Stream.of(
                List.of(),
                List.of("status", "RECEIVED"),
                List.of("companyCode", OTHER_CODE),
                List.of("mine", "false"),
                List.of("status", "RECEIVED", "companyCode", OTHER_CODE, "mine", "false"));
    }

    private static Object[] query(String q, List<String> extra, String otherCode) {
        List<Object> query = new ArrayList<>(List.of("q", q));
        extra.forEach(part -> query.add(OTHER_CODE.equals(part) ? otherCode : part));
        return query.toArray();
    }

    @ParameterizedTest(name = "q with {0}")
    @MethodSource("customerFilters")
    @DisplayName("B8-AC-03 a customer's search of one or two characters finds nothing, and counts nothing, of another "
            + "company's issue or a colleague's private issue, whatever filters go with it")
    void shortSearchKeepsTheCustomerScope(List<String> extra) {
        World w = flow.world();
        Company other = fixtures.createCompany(w.admin());
        Session carol = fixtures.customer(w.admin(), other.code());
        Issue own = fixtures.fileIssue(w.reporter(), NewIssue.in(w.code()).title("환불 Invoice").content("Paid"));
        fixtures.fileIssue(w.colleague(), NewIssue.in(w.code()).privateIssue().title("급여 환불").content("명세"));
        fixtures.fileIssue(carol, NewIssue.in(other.code()).title("합병 환불").content("계획"));
        boolean narrowedToOther = extra.contains(OTHER_CODE);

        SoftAssertions softly = new SoftAssertions();
        for (String hidden : List.of("급여", "급", "명세", "합병", "합", "계획")) {
            ApiResponse page = list(w.reporter(), query(hidden, extra, other.code()));
            softly.assertThat(keys(page)).as("items for %s, which only issues the customer cannot see hold, %s", hidden,
                    extra).isEmpty();
            softly.assertThat(total(page)).as("totalElements for %s, %s", hidden, extra).isZero();
        }
        for (String shared : List.of("환불", "환")) {
            ApiResponse page = list(w.reporter(), query(shared, extra, other.code()));
            softly.assertThat(keys(page)).as("items for %s, which the customer's own issue also holds, %s", shared,
                    extra).isEqualTo(narrowedToOther ? List.of() : List.of(own.key()));
            softly.assertThat(total(page)).as("totalElements for %s, %s", shared, extra)
                    .isEqualTo(narrowedToOther ? 0 : 1);
        }
        softly.assertAll();
    }

    @Test
    @DisplayName("B8-AC-03 with a console filter (status, company, assignee, reporter, label, SLA) a search of one or two "
            + "characters only narrows what the agent sees, and never reaches a company not assigned to the agent")
    void shortSearchWithConsoleFiltersOnlyNarrows() {
        World w = flow.world();
        Company other = fixtures.createCompany(w.admin());
        Session carol = fixtures.customer(w.admin(), other.code());
        Issue assigned = fixtures.fileIssue(w.reporter(), NewIssue.in(w.code()).title("환불 Printer").content("A"));
        Issue confirmed = flow.fileIn(w.colleague(), NewIssue.in(w.code()).title("환불 Scanner").content("B"), w.agent(),
                "CONFIRMED");
        Issue without = fixtures.fileIssue(w.reporter(), NewIssue.in(w.code()).title("Toner").content("C"));
        fixtures.fileIssue(carol, NewIssue.in(other.code()).title("환불 합병").content("D"));
        flow.assign(w.agent(), assigned.key(), w.agent().accountId());
        long label = LabelSteps.id(LabelSteps.create(w.admin(), w.code()));
        LabelSteps.add(w.agent(), assigned.key(), label);
        LabelSteps.add(w.agent(), without.key(), label);
        Map<List<Object>, List<String>> expected = new LinkedHashMap<>();
        expected.put(List.of(), List.of(assigned.key(), confirmed.key()));
        expected.put(List.of("status", "CONFIRMED"), List.of(confirmed.key()));
        expected.put(List.of("companyCode", w.code()), List.of(assigned.key(), confirmed.key()));
        expected.put(List.of("companyCode", other.code()), List.of());
        expected.put(List.of("assignee", "me"), List.of(assigned.key()));
        expected.put(List.of("assignee", "none"), List.of(confirmed.key()));
        expected.put(List.of("reporter", w.colleague().accountId()), List.of(confirmed.key()));
        expected.put(List.of("label", label), List.of(assigned.key()));

        SoftAssertions softly = new SoftAssertions();
        for (String q : List.of("환불", "환")) {
            expected.forEach((filter, keys) -> {
                List<Object> query = new ArrayList<>(List.of("q", q));
                query.addAll(filter);
                ApiResponse page = list(w.agent(), query.toArray());
                softly.assertThat(keys(page)).as("issues found by %s with %s", q, filter)
                        .containsExactlyInAnyOrderElementsOf(keys);
                softly.assertThat(total(page)).as("totalElements for %s with %s", q, filter).isEqualTo(keys.size());
            });
            for (String sla : List.of("breached", "warning")) {
                ApiResponse page = list(w.agent(), "q", q, "sla", sla);
                softly.assertThat(keys(page)).as("issues found by %s with sla=%s", q, sla)
                        .isSubsetOf(assigned.key(), confirmed.key());
                softly.assertThat(total(page)).as("totalElements for %s with sla=%s", q, sla)
                        .isEqualTo(keys(page).size());
            }
            ApiResponse ofOther = list(w.agent(), "q", q.equals("환") ? "합" : "합병");
            softly.assertThat(keys(ofOther)).as("issues found by a word only the unassigned company's issue holds")
                    .isEmpty();
            softly.assertThat(total(ofOther)).as("totalElements for a word of the unassigned company").isZero();
        }
        softly.assertAll();
    }

    // B8-AC-04

    @ParameterizedTest(name = "q={0}")
    @ValueSource(strings = {"환", "환불"})
    @DisplayName("B8-AC-04 totalElements of a search of one or two characters is the number of issues it finds, on "
            + "every page and on a page past the last one")
    void shortSearchCountsWhatItFinds(String q) {
        World w = flow.world();
        Issue first = fixtures.fileIssue(w.reporter(), NewIssue.in(w.code()).title("환불 하나").content("A"));
        fixtures.fileIssue(w.reporter(), NewIssue.in(w.code()).title("Toner").content("B"));
        Issue second = fixtures.fileIssue(w.reporter(), NewIssue.in(w.code()).title("Scanner").content("환불 둘"));
        Issue third = fixtures.fileIssue(w.reporter(), NewIssue.in(w.code()).title("환불 셋").content("C"));

        ApiResponse page1 = listRequest(w.agent(), "q", q, "page", 1, "size", 2).expect(200);
        ApiResponse page2 = listRequest(w.agent(), "q", q, "page", 2, "size", 2).expect(200);
        ApiResponse page3 = listRequest(w.agent(), "q", q, "page", 3, "size", 2).expect(200);

        assertThat(keys(page1)).as("page 1").containsExactly(third.key(), second.key());
        assertThat(keys(page2)).as("page 2").containsExactly(first.key());
        assertThat(keys(page3)).as("page 3, past the last one").isEmpty();
        for (ApiResponse page : List.of(page1, page2, page3)) {
            assertThat(total(page)).as("totalElements of %s", page.request()).isEqualTo(3);
        }
        assertThat(page1.jsonLong("totalPages")).as("totalPages").isEqualTo(2);
    }

    // B8-AC-05

    @Test
    @DisplayName("B8-AC-05 after filing and editing, a search of one or two characters finds the new title and content "
            + "and no longer the old ones, and it does not find a purged issue")
    void shortSearchFollowsEditsAndPurges() {
        World w = flow.world();
        Issue issue = fixtures.fileIssue(w.reporter(), NewIssue.in(w.code()).title("갑을 문의").content("옛글 내용"));
        Map<String, List<String>> beforeEdit = new LinkedHashMap<>();
        for (String q : List.of("갑을", "갑", "옛글", "옛")) {
            beforeEdit.put(q, keys(list(w.agent(), "q", q)));
        }

        flow.edit(w.reporter(), issue.key(), Map.of("title", "병정 문의", "content", "새글 내용"));

        SoftAssertions softly = new SoftAssertions();
        beforeEdit.forEach((q, keys) -> softly.assertThat(keys).as("issues found by %s before the edit", q)
                .containsExactly(issue.key()));
        for (String q : List.of("병정", "병", "새글", "새")) {
            softly.assertThat(keys(list(w.agent(), "q", q))).as("issues found by %s after the edit", q)
                    .containsExactly(issue.key());
            softly.assertThat(keys(list(w.reporter(), "q", q))).as("issues the customer finds by %s after the edit", q)
                    .containsExactly(issue.key());
        }
        for (String q : List.of("갑을", "갑", "옛글", "옛")) {
            softly.assertThat(keys(list(w.agent(), "q", q))).as("issues found by %s after the edit", q).isEmpty();
        }
        softly.assertAll();

        TrashSteps.delete(w.admin(), issue.key());
        TrashSteps.purge(w.admin(), issue.key());

        for (String q : List.of("병정", "병", "새글")) {
            ApiResponse page = list(w.agent(), "q", q);
            assertThat(keys(page)).as("issues found by %s after the purge", q).isEmpty();
            assertThat(total(page)).as("totalElements for %s after the purge", q).isZero();
        }
    }
}
