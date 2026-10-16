package io.popdesk.sla;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.issue.TalkFlow;
import io.popdesk.support.AppTest;
import io.popdesk.support.Fixtures;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.ReceivedMail;
import io.popdesk.support.TestClock;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;

/**
 * Steps of slice 8b, through the API only: the SLA alert items of one issue in a person's inbox, a run of
 * {@code sla-recalculate} at a chosen time, and the people around one company that the alert tests and the
 * summary tests both need. Built on {@link SlaSteps}; every step expects its one status and fails with an
 * {@link AssertionError} otherwise.
 *
 * <p>Public, with public methods, so that the summary and statistics tests (server tab 2) and the hidden E2E checks
 * can use it.
 */
public final class SlaAlertSteps {

    public static final String RESPONSE_WARNING = "SLA_RESPONSE_WARNING";
    public static final String RESPONSE_BREACHED = "SLA_RESPONSE_BREACHED";
    public static final String RESOLUTION_WARNING = "SLA_RESOLUTION_WARNING";
    public static final String RESOLUTION_BREACHED = "SLA_RESOLUTION_BREACHED";
    public static final String RECALCULATED = "SLA_RECALCULATED";

    /** The four kinds about one goal of one issue. */
    public static final Set<String> GOAL_KINDS = Set.of(RESPONSE_WARNING, RESPONSE_BREACHED, RESOLUTION_WARNING,
            RESOLUTION_BREACHED);

    /**
     * The fields of a console inbox item from slice 8b on: those of {@code inbox.InboxSteps.CONSOLE_ITEM_FIELDS}
     * with {@code slaSummary}.
     */
    public static final Set<String> CONSOLE_ITEM_FIELDS = Set.of("id", "kind", "issue", "companyCode", "commentId",
            "actor", "createdAt", "readAt", "task", "linkedIssue", "slaSummary");

    /** A title with a line break and quotes, the one of the {@code notify/__snapshots__} files. */
    public static final String SNAPSHOT_TITLE = "Printer \"B2\" jams\nafter toner change";

    private final Fixtures fixtures;
    private final TestClock clock;
    private final SlaSteps sla;

    public SlaAlertSteps(Fixtures fixtures, TestClock clock, SlaSteps sla) {
        this.fixtures = fixtures;
        this.clock = clock;
        this.sla = sla;
    }

    // Runs of the job

    /**
     * Moves the app clock forward to {@code at} in one step and waits for the run of {@code sla-recalculate} at that
     * time; gives {@code at}. The job runs once a minute of app time passed since its last run, so the run before
     * must be at least a minute before {@code at} (as after {@link SlaSteps#recalculateNow}); no run comes between
     * the clock's time now and {@code at}.
     */
    public Instant runAt(Session admin, Instant at) {
        sla.advanceTo(at);
        SlaSteps.awaitRecalculation(admin, at);
        return at;
    }

    /** {@link #runAt} at {@code minutes} after the issue was filed. */
    public Instant runAfterFiling(Session admin, Issue issue, long minutes) {
        return runAt(admin, SlaSteps.createdAt(issue).plus(Duration.ofMinutes(minutes)));
    }

    /** {@link #runAfterFiling} with whole seconds, for the times right around a warning time or a due time. */
    public Instant runAfterFiling(Session admin, Issue issue, Duration after) {
        return runAt(admin, SlaSteps.createdAt(issue).plus(after));
    }

    /** {@link SlaSteps#recalculateNow} {@code times} times; gives the time of the last run. */
    public Instant runs(Session admin, int times) {
        Instant at = clock.instant();
        for (int i = 0; i < times; i++) {
            at = sla.recalculateNow(admin);
        }
        return at;
    }

    // Items

    /** The caller's console items about the issue of the four goal kinds, newest first. */
    public static List<JsonNode> alerts(Session staff, String key) {
        return TalkFlow.inboxOf(staff, key).stream().filter(n -> GOAL_KINDS.contains(TalkFlow.kind(n))).toList();
    }

    /** The kinds of {@link #alerts}, newest first. */
    public static List<String> alertKinds(Session staff, String key) {
        return TalkFlow.kinds(alerts(staff, key));
    }

    /** The caller's items about the issue of this kind, newest first. */
    public static List<JsonNode> alertsOf(Session staff, String key, String kind) {
        return alerts(staff, key).stream().filter(n -> kind.equals(TalkFlow.kind(n))).toList();
    }

    /** Fails unless the caller has exactly one item of {@code kind} about the issue; gives it. */
    public static JsonNode oneAlert(Session staff, String key, String kind) {
        List<JsonNode> found = alertsOf(staff, key, kind);
        assertThat(found).as("%s items of %s about %s", kind, staff.email(), key).hasSize(1);
        return found.getFirst();
    }

    /**
     * Checks an SLA item about one goal: its fields are those of a console item, the kind and the issue key, the
     * company code, and {@code actor}, {@code commentId}, {@code task}, {@code linkedIssue}, {@code slaSummary} are
     * null.
     */
    public static void assertAlert(JsonNode item, String kind, String key, String companyCode) {
        assertThat(new TreeSet<>(item.propertyNames())).as("fields of %s", item)
                .isEqualTo(new TreeSet<>(CONSOLE_ITEM_FIELDS));
        assertThat(TalkFlow.kind(item)).as("kind of %s", item).isEqualTo(kind);
        assertThat(item.path("issue").path("key").asString(null)).as("issue.key of %s", item).isEqualTo(key);
        assertThat(item.path("companyCode").asString(null)).as("companyCode of %s", item).isEqualTo(companyCode);
        for (String field : List.of("actor", "commentId", "task", "linkedIssue", "slaSummary")) {
            assertThat(TalkFlow.isNull(item, field)).as("%s is null in %s", field, item).isTrue();
        }
    }

    /** The customer's portal items about the issue, of any kind. */
    public static List<JsonNode> portalItems(Session customer, String key) {
        return TalkFlow.inboxOf(customer, key);
    }

    // People

    /** Sets the account's language ({@code PATCH .../me}); mail to it is written in that language. */
    public static void language(Session person, String locale) {
        person.client().patch(person.api("/me")).json(Map.of("locale", locale)).expect(200);
    }

    /** A new admin, signed in, who has done nothing yet. */
    public Session newAdmin(Session admin) {
        return fixtures.admin(admin);
    }

    // The issue as it was before

    /** What an alert must not change on the issue: {@code version}, {@code updatedAt}, and the history length. */
    public record Untouched(long version, Instant updatedAt, int history) {}

    public static Untouched untouched(IssueFlow flow, Session staff, String key) {
        JsonNode detail = flow.detail(staff, key);
        return new Untouched(detail.path("version").asLong(-1), IssueFlow.time(detail, "updatedAt"),
                flow.history(staff, key).size());
    }

    // Mail and snapshots

    /** The console link of an issue, as mail and webhook messages give it. */
    public static String consoleLink(String key) {
        return "http://" + AppTest.CONSOLE_HOST + "/issues/" + key;
    }

    /**
     * SLA mail about the issue: the subject names the issue key and {@code SLA}, as the snapshots of the overdue
     * mail in both languages do. Other mail about the issue (an assignment, a status) does not count.
     */
    public static List<ReceivedMail> mailAbout(List<ReceivedMail> mail, String key) {
        return mail.stream().filter(m -> m.subject().contains(key) && m.subject().contains("SLA")).toList();
    }

    /** The mail with its link as {@code {link}}, the key as {@code {key}}, and the company code as {@code {code}}. */
    public static String snapshotForm(ReceivedMail m, String key, String code, String link) {
        String text = m.text().replace("\r\n", "\n").replace(link, "{link}").replace(key, "{key}")
                .replace(code, "{code}").stripTrailing();
        return "Subject: " + m.subject().replace(key, "{key}").replace(code, "{code}") + "\n\n" + text;
    }

    /**
     * Fails unless {@code actual} equals the file {@code name} in {@code notify/__snapshots__/}. When they differ,
     * the actual text is written to {@code target/snapshots/} to compare, as the snapshot tests of {@code notify} do.
     */
    public static void assertSnapshot(String name, String actual) {
        String expected = snapshot(name);
        if (!actual.equals(expected)) {
            Path out = Path.of("target", "snapshots", name);
            try {
                Files.createDirectories(out.getParent());
                Files.writeString(out, actual + "\n", StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        assertThat(actual).as("snapshot %s (actual written to target/snapshots/%s)", name, name).isEqualTo(expected);
    }

    private static String snapshot(String name) {
        String resource = "notify/__snapshots__/" + name;
        try (InputStream in = SlaAlertSteps.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                return "(no snapshot " + resource + ")";
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).stripTrailing();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
