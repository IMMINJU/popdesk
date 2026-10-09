package io.popdesk.issue;

import static io.popdesk.issue.IssueSteps.keys;
import static io.popdesk.issue.IssueSteps.numberOf;
import static io.popdesk.issue.IssueSteps.text;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueSteps.Scene;
import io.popdesk.issue.StatementLog.Window;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.AppLog;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Concurrent writes under one company and one issue, with PostgreSQL logging every statement so
 * the tests can read deadlocks and lock clauses from the server log. This class has a context of
 * its own ({@link StatementLog.Config}).
 *
 * <p>At most 16 requests run at once, so uploads never meet the limit of 16 running uploads
 * (S0-AC-99).
 */
@Import(StatementLog.Config.class)
class IssueConcurrencyTest extends BareAppTest {

    private static final int THREADS = 16;

    /** {@code FOR UPDATE} without {@code NO KEY} in front, in any case and spacing. */
    private static final Pattern PLAIN_FOR_UPDATE =
            Pattern.compile("(?i)(?<!no\\s{1,10}key\\s{1,10})\\bfor\\s+update\\b(?!\\s+(of\\s+[^\\s]+\\s+)?skip\\s+locked)");

    /** Transaction statements as PostgreSQL logs them: {@code statement: BEGIN}, {@code execute S_1: COMMIT}. */
    private static final Pattern BEGIN = Pattern.compile("(?i)(statement|execute [^:]*):\\s*(begin|start transaction)\\b");
    private static final Pattern END = Pattern.compile("(?i)(statement|execute [^:]*):\\s*(commit|rollback|end)\\b");

    @Autowired
    PostgreSQLContainer postgres;

    private IssueSteps steps;
    private StatementLog sql;

    @BeforeEach
    void setUp() {
        steps = new IssueSteps(fixtures, clock);
        sql = new StatementLog(db, postgres);
    }

    @Test
    @DisplayName("S0-AC-44 20 filings with and without attachments at once, mixed with 400s, get numbers 1 to 20 and no deadlock")
    void concurrentFilingsGetNumbersWithoutGaps() {
        sql.assertLogsEveryStatement();
        Scene s = steps.scene();
        List<Session> customers = new ArrayList<>(List.of(s.customer()));
        for (int i = 0; i < 4; i++) {
            customers.add(fixtures.customer(s.admin(), s.code()));
        }
        List<Supplier<ApiResponse>> calls = new ArrayList<>();
        for (Session customer : customers) {
            for (int i = 0; i < 4; i++) {
                boolean withFile = i % 2 == 0;
                calls.add(() -> file(customer, s.code(), withFile ? "attached.txt" : null, "content"));
            }
            calls.add(() -> file(customer, s.code(), "empty.txt", ""));
        }

        AppLog.Mark appMark = log.mark();
        Window window = sql.start();
        List<ApiResponse> responses = Concurrently.send(THREADS, calls);
        String serverLog = sql.end(window);

        List<ApiResponse> created = responses.stream().filter(r -> r.status() == 201).toList();
        List<ApiResponse> rejected = responses.stream().filter(r -> r.status() != 201).toList();
        assertThat(created).as("201s").hasSize(20);
        assertThat(rejected).as("rejected filings").hasSize(5)
                .allSatisfy(r -> {
                    assertThat(r.status()).isEqualTo(400);
                    assertThat(r.errorCode()).isEqualTo("FILE_EMPTY");
                });
        List<Long> numbers = created.stream().map(r -> numberOf(text(r.json(), "key"))).sorted().toList();
        assertThat(numbers).containsExactlyElementsOf(LongStream.rangeClosed(1, 20).boxed().toList());
        assertThat(created).extracting(r -> text(r.json(), "key")).allMatch(k -> k.startsWith(s.code() + "-"));
        assertThat(keys(s.customer().client().get("/api/portal/issues").query("size", 100).expect(200)))
                .hasSize(20);
        assertThat(serverLog.toLowerCase(Locale.ROOT)).as("PostgreSQL log").doesNotContain("deadlock detected");
        assertThat(log.since(appMark)).as("app log").doesNotContain("40P01");
    }

    @Test
    @DisplayName("S0-AC-51 20 comments and 20 attachments on one issue at once all succeed, without deadlock or plain FOR UPDATE")
    void concurrentCommentsAndAttachments() {
        sql.assertLogsEveryStatement();
        Scene s = steps.scene();
        Session agent = fixtures.agent(s.admin(), s.code());
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        List<Supplier<ApiResponse>> calls = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String n = Integer.toString(i);
            calls.add(() -> comment(s.customer(), issue.key(), "Customer comment " + n));
            calls.add(() -> comment(agent, issue.key(), "Agent comment " + n));
            calls.add(() -> upload(s.customer(), issue.key(), "customer-" + n + ".txt"));
            calls.add(() -> upload(agent, issue.key(), "agent-" + n + ".txt"));
        }

        AppLog.Mark appMark = log.mark();
        Window window = sql.start();
        List<ApiResponse> responses = Concurrently.send(THREADS, calls);
        String serverLog = sql.end(window);

        assertThat(responses).extracting(ApiResponse::status).containsOnly(201);
        String commentsPath = "/api/console/issues/" + issue.key() + "/comments";
        assertThat(agent.client().get(commentsPath).query("size", 100).expect(200).json()
                .path("totalElements").asLong()).isEqualTo(20);
        String attachmentsPath = "/api/console/issues/" + issue.key() + "/attachments";
        assertThat(agent.client().get(attachmentsPath).query("size", 100).expect(200).json()
                .path("totalElements").asLong()).isEqualTo(20);
        assertThat(serverLog.toLowerCase(Locale.ROOT)).as("statements in the window").contains("insert");
        assertThat(serverLog.toLowerCase(Locale.ROOT)).as("PostgreSQL log").doesNotContain("deadlock detected");
        assertThat(plainForUpdates(serverLog)).as("FOR UPDATE without NO KEY").isEmpty();
        assertThat(log.since(appMark)).as("app log").doesNotContain("40P01");
    }

    @Test
    @DisplayName("S0-AC-51 a comment's transaction locks the issue row FOR NO KEY UPDATE before any INSERT")
    void commentLocksIssueBeforeInsert() {
        sql.assertLogsEveryStatement();
        Scene s = steps.scene();
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        String issueTable = new IssueTables(db).find(s.company().legalName(), text(issue.json(), "title"))
                .issue().table().name();
        String content = "Lock order " + Unique.number();

        Window window = sql.start();
        comment(s.customer(), issue.key(), content).expectStatus(201);
        String serverLog = sql.end(window);

        List<String> lines = StatementLog.byProcess(serverLog).values().stream()
                .filter(l -> l.stream().anyMatch(line -> line.contains(content)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No connection wrote the comment " + content));
        int insertOfComment = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(content)) {
                insertOfComment = i;
                break;
            }
        }
        int begin = -1;
        for (int i = insertOfComment; i >= 0; i--) {
            if (BEGIN.matcher(lines.get(i)).find()) {
                begin = i;
                break;
            }
        }
        assertThat(begin).as("BEGIN of the transaction that inserts the comment, in: %s", lines).isNotNegative();
        List<String> transaction = lines.subList(begin, insertOfComment + 1).stream()
                .map(l -> l.toLowerCase(Locale.ROOT))
                .toList();
        assertThat(transaction).as("one transaction from BEGIN to the comment's INSERT")
                .noneMatch(l -> END.matcher(l).find());
        int firstLock = indexOf(transaction, "for no key update");
        int firstInsert = indexOf(transaction, "insert");
        assertThat(firstLock).as("FOR NO KEY UPDATE in the comment's transaction, in: %s", transaction).isNotNegative();
        assertThat(transaction.get(firstLock)).as("the first lock is on the issue table " + issueTable)
                .containsPattern(tableName(issueTable));
        assertThat(firstLock).as("lock before the first INSERT, in: %s", transaction).isLessThan(firstInsert);
    }

    /** {@code table} as a whole name in SQL text, quoted or not. */
    private static Pattern tableName(String table) {
        return Pattern.compile("(?i)(?<![\\w$])\"?" + Pattern.quote(table) + "\"?(?![\\w$])");
    }

    private static int indexOf(List<String> lines, String text) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(text)) {
                return i;
            }
        }
        return -1;
    }

    /** Each {@code FOR UPDATE} without {@code NO KEY}, with the text around it. {@code SKIP LOCKED} queue reads are left out. */
    private static List<String> plainForUpdates(String serverLog) {
        List<String> out = new ArrayList<>();
        Matcher m = PLAIN_FOR_UPDATE.matcher(serverLog);
        while (m.find()) {
            out.add(serverLog.substring(Math.max(0, m.start() - 200), Math.min(serverLog.length(), m.end() + 40)));
        }
        return out;
    }

    private static ApiResponse file(Session customer, String code, String fileName, String fileContent) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("companyCode", code);
        body.put("title", Unique.text("Concurrent"));
        if (fileName == null) {
            return customer.client().post("/api/portal/issues").json(body).send();
        }
        return customer.client().post("/api/portal/issues")
                .jsonPart("issue", body)
                .file("files", fileName, "text/plain", fileContent.getBytes(StandardCharsets.UTF_8))
                .send();
    }

    private static ApiResponse comment(Session session, String key, String content) {
        return session.client().post(session.api("/issues/" + key + "/comments"))
                .json(Map.of("content", content))
                .send();
    }

    private static ApiResponse upload(Session session, String key, String fileName) {
        return session.client().post(session.api("/issues/" + key + "/attachments"))
                .file("file", fileName, "text/plain", ("content of " + fileName).getBytes(StandardCharsets.UTF_8))
                .send();
    }
}
