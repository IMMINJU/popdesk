package io.popdesk.issue;

import static io.popdesk.issue.IssueFlow.listRequest;
import static io.popdesk.issue.IssueFlow.total;
import static io.popdesk.issue.IssueSteps.keys;

import com.zaxxer.hikari.HikariDataSource;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.NewIssue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Wait;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.sql.DataSource;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The plan PostgreSQL picks for a search of one or two characters that almost every issue holds (slice b10).
 * The plans are the ones of the statements the app sent: {@code auto_explain} writes each plan to the database's
 * log while a test runs, and the test reads them from the container's output. No SQL of the app is copied here.
 *
 * <p>A context and a database of its own: the share of issues that hold the word, and the statistics ANALYZE
 * keeps, belong to this class alone, whatever other classes filed. The settings that turn {@code auto_explain}
 * on and pick the plan cache mode are set on the database for the time of one test and taken off at its end;
 * the app's pool is emptied both times, so its connections start again with them or without them.
 */
@TestPropertySource(properties = ShortSearchPlanTest.OWN_CONTEXT)
class ShortSearchPlanTest extends BareAppTest {

    /** A property the app does not read; it keeps this context and its database apart. */
    static final String OWN_CONTEXT = "issue.test.context=short-search-plan";

    /**
     * Issues in the database before a test searches. On 0.6.0, 300 are enough for a sequential scan with the
     * values known; with them unknown, the gram index costs a tenth of the scan here.
     */
    private static final int ISSUES = 600;

    /** One issue in this many does not hold the words. */
    private static final int WITHOUT_EVERY = 33;

    /** Almost every issue holds these: one character and two. */
    private static final List<String> WORDS = List.of("e", "in");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private PostgreSQLContainer postgres;

    @Autowired
    private DataSource dataSource;

    /** The plan cache modes of PostgreSQL: a plan for the values sent, and one for any value. */
    enum PlanMode {
        VALUES_KNOWN("force_custom_plan"),
        VALUES_UNKNOWN("force_generic_plan");

        final String setting;

        PlanMode(String setting) {
            this.setting = setting;
        }
    }

    /** One plan {@code auto_explain} wrote: the statement as sent and its plan. */
    private record Logged(String queryText, JsonNode root) {}

    // B10-AC-01

    @ParameterizedTest(name = "{0}")
    @EnumSource(PlanMode.class)
    @DisplayName("B10-AC-01 after ANALYZE of issues that almost all hold the word, a search of one or two characters "
            + "in the console list, the portal list, and the count of a page past the last one reads issue_search_grams_idx "
            + "and never a Seq Scan on issue that calls issue_search_grams in its Filter")
    void commonShortWordReadsTheGramIndex(PlanMode mode) {
        fillAndAnalyze();
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Session customer = fixtures.customer(admin, company.code());
        Session agent = fixtures.agent(admin, company.code());
        List<String> own = List.of(
                fixtures.fileIssue(customer, NewIssue.in(company.code()).title("Printer in hall").content("Paper jams"))
                        .key(),
                fixtures.fileIssue(customer, NewIssue.in(company.code()).title("Login fails").content("Since Monday"))
                        .key());

        SoftAssertions softly = new SoftAssertions();
        withPlanLog(mode, () -> {
            for (Session viewer : List.of(agent, customer)) {
                for (String q : WORDS) {
                    String what = viewer.app() + " list, q=" + q;
                    int from = postgres.getLogs().length();
                    ApiResponse page = listRequest(viewer, "q", q, "size", 100).expect(200);
                    softly.assertThat(keys(page)).as("issues of %s", what).containsExactlyInAnyOrderElementsOf(own);
                    checkPlans(softly, mode, what, awaitPlans(what, from, 1));

                    String past = viewer.app() + " list past the last page, q=" + q;
                    from = postgres.getLogs().length();
                    ApiResponse empty = listRequest(viewer, "q", q, "page", 2, "size", 100).expect(200);
                    softly.assertThat(keys(empty)).as("issues of %s", past).isEmpty();
                    softly.assertThat(total(empty)).as("totalElements of %s", past).isEqualTo(own.size());
                    List<Logged> plans = awaitPlans(past, from, 2);
                    softly.assertThat(plans.stream().map(p -> p.queryText().strip().toLowerCase(Locale.ROOT)))
                            .as("statements of %s", past).anyMatch(text -> text.startsWith("select count(*)"));
                    checkPlans(softly, mode, past, plans);
                }
            }
        });
        softly.assertAll();
    }

    /** Fails, softly, when a plan was not made in {@code mode}, reads no gram index, or scans issue calling the function. */
    private static void checkPlans(SoftAssertions softly, PlanMode mode, String what, List<Logged> plans) {
        for (Logged plan : plans) {
            String statement = what + ": " + plan.queryText().strip();
            softly.assertThat(plan.root().path("Settings").path("plan_cache_mode").asString(""))
                    .as("plan_cache_mode the plan was made with, %s", statement).isEqualTo(mode.setting);
            List<JsonNode> nodes = new ArrayList<>();
            collect(plan.root().path("Plan"), nodes);
            softly.assertThat(nodes.stream().map(node -> node.path("Index Name").asString("")).toList())
                    .as("indexes the plan reads, %s", statement).contains("issue_search_grams_idx");
            softly.assertThat(nodes.stream()
                            .filter(node -> node.path("Node Type").asString("").equals("Seq Scan")
                                    && node.path("Relation Name").asString("").equals("issue")
                                    && node.path("Filter").asString("").contains("issue_search_grams"))
                            .map(JsonNode::toString).toList())
                    .as("Seq Scan on issue with issue_search_grams in its Filter, %s", statement).isEmpty();
        }
    }

    private static void collect(JsonNode node, List<JsonNode> into) {
        if (node.isMissingNode()) {
            return;
        }
        into.add(node);
        for (JsonNode child : node.path("Plans")) {
            collect(child, into);
        }
    }

    // The data

    /**
     * Makes sure the database holds {@link #ISSUES} issues, all but one in {@link #WITHOUT_EVERY} with both words,
     * then runs VACUUM ANALYZE so the planner knows how common they are. Issues an earlier test of this class filed
     * count; none of them is needed.
     *
     * <p>VACUUM, as autovacuum does in time, also moves the gram index's pending list into the index. With ANALYZE
     * alone the planner's cost of the index went with the size of that list, and the same query read the index at
     * 1,200 and 3,000 issues but not at 2,000.
     */
    private void fillAndAnalyze() {
        long have = db.count("select count(*) from issue");
        if (have < ISSUES) {
            Session admin = fixtures.signInAdmin();
            Company company = fixtures.createCompany(admin);
            Session customer = fixtures.customer(admin, company.code());
            for (long n = have; n < ISSUES; n++) {
                if (n % 10 == 0) {
                    // Ten new issues a minute for one account (the app's limit).
                    clock.advance(Duration.ofSeconds(61));
                }
                NewIssue issue = n % WITHOUT_EVERY == 0
                        ? NewIssue.in(company.code()).title("Toast jam " + n).content("A short worry about toast " + n)
                        : NewIssue.in(company.code()).title("Printer in hall " + n)
                                .content("The paper feed stops in the morning when the tray is full. Line " + n
                                        + " of the weekly report, please check the rollers.");
                fixtures.fileIssue(customer, issue);
            }
        }
        superuser("vacuum analyze issue");
    }

    // The plans

    /**
     * Runs {@code work} while the app's new connections load {@code auto_explain}, which logs the plan of every
     * statement as JSON with the planner settings that differ from the default, and plan in {@code mode}.
     */
    private void withPlanLog(PlanMode mode, Runnable work) {
        String database = "\"" + postgres.getDatabaseName() + "\"";
        superuser("load 'auto_explain'",
                "alter database " + database + " set session_preload_libraries = 'auto_explain'",
                "alter database " + database + " set auto_explain.log_min_duration = 0",
                "alter database " + database + " set auto_explain.log_format = 'json'",
                "alter database " + database + " set auto_explain.log_settings = on",
                "alter database " + database + " set plan_cache_mode = '" + mode.setting + "'");
        renewAppConnections();
        try {
            work.run();
        } finally {
            superuser("alter database " + database + " reset plan_cache_mode",
                    "alter database " + database + " reset auto_explain.log_settings",
                    "alter database " + database + " reset auto_explain.log_format",
                    "alter database " + database + " reset auto_explain.log_min_duration",
                    "alter database " + database + " reset session_preload_libraries");
            renewAppConnections();
        }
    }

    /** Closes the app's pooled connections, idle ones now and busy ones when they come back. */
    private void renewAppConnections() {
        try {
            dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean().softEvictConnections();
        } catch (SQLException e) {
            throw new IllegalStateException("The app's DataSource is not a Hikari pool", e);
        }
    }

    /**
     * Waits until the log after {@code from} holds {@code count} plans of statements that call
     * {@code issue_search_grams}, and gives them.
     */
    private List<Logged> awaitPlans(String what, int from, int count) {
        return Wait.until("plans of " + what + " in the database log", () -> {
            List<Logged> found = plansSince(from);
            return found.size() >= count ? found : null;
        });
    }

    /** The plans {@code auto_explain} logged after {@code from} for statements that call issue_search_grams. */
    private List<Logged> plansSince(int from) {
        String log = postgres.getLogs();
        List<Logged> plans = new ArrayList<>();
        int at = log.indexOf("plan:", from);
        while (at >= 0) {
            int start = log.indexOf('{', at);
            int end = start < 0 ? -1 : endOfObject(log, start);
            if (end < 0) {
                break;
            }
            JsonNode root = JSON.readTree(log.substring(start, end));
            String text = root.path("Query Text").asString("");
            if (text.contains("issue_search_grams")) {
                plans.add(new Logged(text, root));
            }
            at = log.indexOf("plan:", end);
        }
        return plans;
    }

    /** The index after the closing brace of the JSON object that starts at {@code start}, or -1 if it is not all there. */
    private static int endOfObject(String text, int start) {
        int depth = 0;
        boolean inString = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return i + 1;
            }
        }
        return -1;
    }

    /** Runs statements as the container's superuser, in the app's schema. */
    private void superuser(String... statements) {
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                        postgres.getPassword());
                Statement s = c.createStatement()) {
            c.setSchema(db.schema());
            for (String sql : statements) {
                s.execute(sql);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not run " + List.of(statements), e);
        }
    }
}
