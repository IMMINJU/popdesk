package io.popdesk.boundary;

import static io.popdesk.issue.IssueFlow.NO_KEY;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.boundary.BoundaryList.Entry;
import io.popdesk.issue.IssueFlow;
import io.popdesk.support.ApiRequest;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import io.popdesk.trash.TrashScene;
import io.popdesk.trash.TrashSteps;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An issue in the trash is 404 on every issue path of both apps, to admins too (S7A-AC-03, TRASH-1, COM-1). The
 * paths are not listed by hand: they are the operations of {@link BoundaryList} (which {@link BoundaryListTest}
 * holds to both contract files) whose path has {@code {key}}, but the trash paths. So an issue path added later is
 * checked here without a change.
 *
 * <p>The issue has something of every kind under it ({@link TrashScene}), and the other path variables are filled
 * with the IDs of those things, so that a path that finds the file, the comment, or the task without asking whether
 * its issue is in the trash answers with it. Before the delete every read answers 2xx with those IDs. A write is sent
 * with a body that would be valid. Each answer is compared with the same request for a key nobody has.
 */
class TrashedIssueNotFoundTest extends BareAppTest {

    private static final Pattern VARIABLE = Pattern.compile("\\{([^}]+)}");

    private IssueFlow flow;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
    }

    /** The operations of both apps that name an issue by its key, but the trash paths. */
    private static List<Entry> issueOperations() {
        return BoundaryList.ENTRIES.stream()
                .filter(e -> e.path().contains("{key}"))
                .filter(e -> !e.path().startsWith("/api/console/trash"))
                .toList();
    }

    private static boolean portal(Entry e) {
        return e.path().startsWith("/api/portal/");
    }

    private static boolean read(Entry e) {
        return e.method().equals("GET") || e.method().equals("HEAD");
    }

    /** The path with {@code {key}} as {@code key} and every other variable filled from the scene. */
    private static String fill(Entry e, TrashScene s, String key) {
        Matcher m = VARIABLE.matcher(e.path());
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String before = e.path().substring(0, m.start());
            m.appendReplacement(out, Matcher.quoteReplacement(value(e, s, key, m.group(1), before)));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String value(Entry e, TrashScene s, String key, String name, String before) {
        if (name.equals("key")) {
            return key;
        }
        if (name.equals("otherKey")) {
            return s.other().key();
        }
        if (name.equals("noteId")) {
            return String.valueOf(s.note());
        }
        if (name.equals("id") && before.endsWith("/attachments/")) {
            return s.issueFile().id();
        }
        if (name.equals("id") && before.endsWith("/comments/")) {
            return String.valueOf(portal(e) ? s.customerComment().id() : s.staffComment().id());
        }
        if (name.equals("id") && before.endsWith("/labels/")) {
            return String.valueOf(s.label());
        }
        if (name.equals("id") && before.endsWith("/tasks/")) {
            return String.valueOf(s.task());
        }
        throw new AssertionError("A path variable this test does not fill: {" + name + "} in " + e.operation());
    }

    /** The request of an operation by {@code caller}, with a body that would be valid for a live issue. */
    private ApiRequest request(Entry e, Session caller, TrashScene s, String key, long version) {
        String path = fill(e, s, key);
        ApiRequest request = caller.client().request(e.method(), path);
        if (path.endsWith("/mention-candidates")) {
            request.query("internal", false);
        }
        if (e.method().equals("POST") && path.endsWith("/attachments")) {
            return request.file("file", "after-delete-" + Unique.number() + ".txt", "text/plain",
                    "after the delete".getBytes(StandardCharsets.UTF_8));
        }
        Map<String, Object> body = body(e, path, caller, s, version);
        return body == null ? request : request.json(body);
    }

    private Map<String, Object> body(Entry e, String path, Session caller, TrashScene s, long version) {
        if (e.method().equals("GET") || e.method().equals("HEAD") || e.method().equals("DELETE")) {
            return null;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        if (path.endsWith("/close") || path.endsWith("/reopen") || e.path().endsWith("/labels/{id}")) {
            return null;
        } else if (path.endsWith("/status")) {
            body.put("status", "CLOSED");
        } else if (path.endsWith("/assignee")) {
            body.put("accountId", caller.accountId());
        } else if (path.endsWith("/due-date")) {
            body.put("dueDate", LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).plusDays(20).toString());
        } else if (path.endsWith("/links")) {
            body.put("issueKey", s.other().key());
            body.put("type", "RELATED");
        } else if (e.path().endsWith("/tasks") || e.path().endsWith("/tasks/{id}")) {
            body.put("title", "After the delete");
        } else if (path.contains("/comments") || path.contains("/notes")) {
            body.put("content", "After the delete");
        } else if (e.path().endsWith("/issues/{key}")) {
            body.put("title", "After the delete");
            body.put("version", version);
        }
        return body;
    }

    /** The callers of an operation: the reporting customer on the portal; the admin and the agent on the console. */
    private static List<Session> callers(Entry e, TrashScene s) {
        return portal(e) ? List.of(s.reporter()) : List.of(s.admin(), s.agent());
    }

    @Test
    @DisplayName("S7A-AC-03 every path of both apps with an issue key but the trash paths, read and write, by the reporting customer, the assigned agent, and the admin, gives the 404 of a key nobody has once the issue is in the trash, with the IDs of its file, comments, task, note, label, and link; deleting it again too")
    void everyIssuePathIsNotFound() {
        TrashScene s = TrashScene.build(fixtures, flow, clock, "IN_PROGRESS");
        long version = flow.version(s.admin(), s.key());
        List<Entry> operations = issueOperations();
        assertThat(operations.stream().map(Entry::operation))
                .as("issue operations found in the boundary list")
                .contains("GET /api/portal/issues/{key}/attachments/{id}", "GET /api/console/issues/{key}/history",
                        "DELETE /api/console/issues/{key}");
        SoftAssertions before = new SoftAssertions();
        for (Entry e : operations.stream().filter(TrashedIssueNotFoundTest::read).toList()) {
            for (Session caller : callers(e, s)) {
                ApiResponse live = request(e, caller, s, s.key(), version).send();
                before.assertThat(live.status()).as("before the delete: " + live.request() + " -> " + live.snippet())
                        .isBetween(200, 299);
            }
        }
        before.assertAll();

        TrashSteps.delete(s.admin(), s.key());

        SoftAssertions soft = new SoftAssertions();
        List<String> checked = new ArrayList<>();
        for (Entry e : operations) {
            for (Session caller : callers(e, s)) {
                ApiResponse answer = request(e, caller, s, s.key(), version).send();
                ApiResponse missing = request(e, caller, s, NO_KEY, version).send();
                soft.assertThatCode(() -> TrashSteps.assertLikeMissing(answer, missing))
                        .as(e.operation() + " by account " + caller.accountId()).doesNotThrowAnyException();
                checked.add(e.operation());
            }
        }
        soft.assertAll();
        assertThat(checked).as("requests sent after the delete").hasSizeGreaterThan(operations.size());
    }

    @Test
    @DisplayName("S7A-AC-03 a filing sent again with the Idempotency-Key of the request that filed the issue is the 404 of a key nobody has once the issue is in the trash; before, it gave the same issue")
    void filingAgainWithTheSameKeyIsNotFound() {
        Session admin = fixtures.admin(flow.admin());
        String code = fixtures.createCompany(admin).code();
        Session reporter = fixtures.customer(admin, code);
        String idempotencyKey = "trash-" + UUID.randomUUID();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("companyCode", code);
        body.put("title", Unique.text("Filed once"));
        body.put("content", "Filed with a key");
        ApiResponse filed = reporter.client().post("/api/portal/issues").header("Idempotency-Key", idempotencyKey)
                .json(body).expect(201);
        String key = filed.jsonText("key");
        ApiResponse again = reporter.client().post("/api/portal/issues").header("Idempotency-Key", idempotencyKey)
                .json(body).send();
        assertThat(again.status()).as("the same filing again, before the delete: " + again.snippet())
                .isBetween(200, 299);
        assertThat(again.jsonText("key")).as("key of the filing sent again").isEqualTo(key);

        TrashSteps.delete(admin, key);
        ApiResponse afterDelete = reporter.client().post("/api/portal/issues").header("Idempotency-Key", idempotencyKey)
                .json(body).send();
        ApiResponse missing = reporter.client().get("/api/portal/issues/" + NO_KEY).send();

        assertThat(afterDelete.status()).as("the same filing after the delete: " + afterDelete.snippet())
                .isEqualTo(404);
        assertThat(afterDelete.errorCode()).as("code, like a key nobody has").isEqualTo(missing.errorCode())
                .isEqualTo("NOT_FOUND");
        assertThat(flow.version(admin, fixtures.fileIssue(reporter, code).key())).as("a new filing still works")
                .isPositive();
    }
}
