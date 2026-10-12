package io.popdesk.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.deploy.AppSteps.Person;
import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Slice 3 on installations that were there before it (ADR D-24, S3-AC-89): the samples kept from
 * 0.1.0 and 0.2.0 in {@code deploy/upgrade/samples/} are restored and the image under test is
 * started on them. The issues of a sample were all filed on the portal by their reporter and never
 * changed state, so after the upgrade they have no assignee, nobody filed them on someone's behalf,
 * their history starts with the filing, and the search finds them.
 *
 * <p>The issues are the keys in the sample's {@code sample.expected.json}. They are read through the
 * console as the sample's admin; the database is only asked whether a migration failed. Each sample
 * is restored once and its stack is shared by the tests of this class (two installations at the
 * end). The accounts and passwords are the ones {@code deploy/upgrade/popdesk_stack.py} gives every
 * sample.
 */
@Tag("deploy")
class IssueUpgradeTest {

    private static final String SAMPLE_ADMIN = "admin@sample.popdesk.test";
    private static final String SAMPLE_ADMIN_PASSWORD = "sample-Admin-Password-1";
    private static final String SAMPLE_PASSWORD = "sample-People-Password-1";
    private static final String SAMPLE_CUSTOMER = "customer@sample.popdesk.test";

    /** A search word is at least this long, so it is more than a stop word of the sample's texts. */
    private static final int WORD_LENGTH = 4;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Per sample version, the stack with the dump restored and the image under test started on it. */
    private static final Map<String, Once<Stack>> UPGRADED = Map.of(
            "0.1.0", new Once<Stack>("restoring the 0.1.0 sample and starting the image", () -> restored("0.1.0")),
            "0.2.0", new Once<Stack>("restoring the 0.2.0 sample and starting the image", () -> restored("0.2.0")));

    /** One issue of a sample, as the console shows it to an admin after the upgrade. */
    private record SampleIssue(String key, JsonNode detail) {

        String title() {
            return detail.path("title").asString("");
        }

        String content() {
            return detail.path("content").asString("");
        }

        /** Whether the title or the content holds {@code word}, in any case. */
        boolean holds(String word) {
            return (title() + "\n" + content()).toLowerCase(Locale.ROOT).contains(word.toLowerCase(Locale.ROOT));
        }
    }

    @AfterAll
    static void cleanUp() {
        for (Once<Stack> upgraded : UPGRADED.values()) {
            Stack stack = upgraded.peek();
            if (stack != null) {
                stack.close();
            }
        }
    }

    private static Path sample(String version, String file) {
        return Repo.require("deploy/upgrade/samples/" + version + "/" + file,
                "the sample kept from " + version + ", deploy/upgrade/README.md");
    }

    private static Stack restored(String version) {
        Path dump = sample(version, "sample.dump");
        Stack stack = Stack.release("issues" + version.replace(".", ""));
        try {
            stack.appEnv("POPDESK_ADMIN_EMAIL", SAMPLE_ADMIN).appEnv("POPDESK_ADMIN_PASSWORD", SAMPLE_ADMIN_PASSWORD);
            stack.up("db");
            stack.db().awaitReady();
            stack.command(Duration.ofMinutes(5), "run", "--rm", "-T", "-v",
                            Repo.forCompose(dump) + ":/tmp/restore.dump:ro", "app", "popdesk", "restore",
                            "/tmp/restore.dump")
                    .expectOk("popdesk restore of " + dump);
            stack.up("app");
            stack.awaitHealthy("app");
            return stack;
        } catch (AssertionError | RuntimeException e) {
            stack.close();
            throw e;
        }
    }

    private static Person admin(Stack stack) {
        return new AppSteps(stack).signIn(SAMPLE_ADMIN, SAMPLE_ADMIN_PASSWORD);
    }

    /** The issues that were in the sample (the keys of its expected file), read one by one on the console. */
    private static List<SampleIssue> sampleIssues(String version, Person admin) {
        Path file = sample(version, "sample.expected.json");
        JsonNode expected;
        try {
            expected = JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new AssertionError("Could not read " + file + ": " + e, e);
        }
        List<String> keys = new ArrayList<>();
        expected.path("issues").forEach(key -> keys.add(key.asString("")));
        assertThat(keys).as("issue keys in %s", file).isNotEmpty().doesNotContain("");
        List<SampleIssue> out = new ArrayList<>();
        for (String key : keys) {
            out.add(new SampleIssue(key, admin.client().get("/api/console/issues/" + key).expect(200).json()));
        }
        return out;
    }

    private static List<JsonNode> items(ApiResponse page) {
        List<JsonNode> out = new ArrayList<>();
        page.json().path("items").forEach(out::add);
        return out;
    }

    private static List<String> keys(ApiResponse page) {
        return items(page).stream().map(item -> item.path("key").asString("")).toList();
    }

    /** The longest word of {@code text} with letters and digits only, or null when none is long enough. */
    private static String longestWord(String text) {
        return Arrays.stream(text.split("[^\\p{L}\\p{N}]+")).filter(word -> word.length() >= WORD_LENGTH)
                .max(Comparator.comparingInt(String::length)).orElse(null);
    }

    private static Instant instant(JsonNode value, String what) {
        try {
            return OffsetDateTime.parse(value.asString("")).toInstant();
        } catch (DateTimeParseException e) {
            throw new AssertionError(what + " is not a date-time: " + value, e);
        }
    }

    @ParameterizedTest(name = "from {0}")
    @ValueSource(strings = {"0.1.0", "0.2.0"})
    @DisplayName("S3-AC-89 on a sample of an older version this image finishes migrating and starts, and every "
            + "issue that was there has no assignee, nobody who filed it on behalf, and no resolved or closed time")
    void sampleIssuesHaveNoAssigneeCreatorOrEndTimes(String version) {
        Stack stack = UPGRADED.get(version).get();
        Db db = stack.db();
        assertThat(db.count("select count(*) from " + Db.quote(db.schema())
                + ".flyway_schema_history where not success")).as("failed migrations").isZero();

        List<SampleIssue> issues = sampleIssues(version, admin(stack));

        for (SampleIssue issue : issues) {
            for (String field : List.of("assignee", "createdBy", "resolvedAt", "closedAt")) {
                assertThat(issue.detail().has(field) && issue.detail().path(field).isNull())
                        .as("%s of %s is there and null in %s", field, issue.key(), issue.detail()).isTrue();
            }
        }
    }

    @ParameterizedTest(name = "from {0}")
    @ValueSource(strings = {"0.1.0", "0.2.0"})
    @DisplayName("S3-AC-89 every issue of an older sample has a history of one entry after the upgrade: CREATED, "
            + "by its reporter, at the time it was filed")
    void sampleIssuesHaveOneCreatedHistoryEntry(String version) {
        Stack stack = UPGRADED.get(version).get();
        Person admin = admin(stack);

        for (SampleIssue issue : sampleIssues(version, admin)) {
            JsonNode reporter = issue.detail().path("reporter").path("accountId");
            assertThat(reporter.isIntegralNumber()).as("reporter of %s in %s", issue.key(), issue.detail()).isTrue();

            ApiResponse history = admin.client().get("/api/console/issues/" + issue.key() + "/history")
                    .query("size", 100).expect(200);

            assertThat(history.json().path("totalElements").asLong(-1))
                    .as("totalElements of the history of %s: %s", issue.key(), history.snippet()).isEqualTo(1);
            assertThat(items(history)).as("history of %s: %s", issue.key(), history.snippet()).hasSize(1);
            JsonNode entry = items(history).getFirst();
            assertThat(entry.path("kind").asString(null)).as("kind of %s", entry).isEqualTo("CREATED");
            assertThat(entry.path("actor").path("accountId").asLong(-1))
                    .as("actor of %s is the reporter of %s", entry, issue.key()).isEqualTo(reporter.asLong());
            assertThat(instant(entry.path("createdAt"), "createdAt of the history entry of " + issue.key()))
                    .as("createdAt of %s is when %s was filed", entry, issue.key())
                    .isEqualTo(instant(issue.detail().path("createdAt"), "createdAt of " + issue.key()));
        }
    }

    @ParameterizedTest(name = "from {0}")
    @ValueSource(strings = {"0.1.0", "0.2.0"})
    @DisplayName("S3-AC-89 after the upgrade the console list finds the issues of an older sample by a word of "
            + "their title and by a word of their content, and no issue without that word")
    void sampleIssuesAreFoundOnTheConsole(String version) {
        Stack stack = UPGRADED.get(version).get();
        Person admin = admin(stack);
        List<SampleIssue> issues = sampleIssues(version, admin);
        int narrowed = 0;

        for (SampleIssue issue : issues) {
            String inTitle = longestWord(issue.title());
            assertThat(inTitle).as("a word of %d letters or more in the title of %s: %s", WORD_LENGTH, issue.key(),
                    issue.title()).isNotNull();
            List<String> words = new ArrayList<>(List.of(inTitle));
            String inContent = longestWord(issue.content());
            if (inContent != null) {
                words.add(inContent);
            }
            for (String word : words) {
                List<String> holding = issues.stream().filter(i -> i.holds(word)).map(SampleIssue::key).toList();

                ApiResponse found = admin.client().get("/api/console/issues").query("q", word).query("size", 100)
                        .expect(200);

                assertThat(keys(found)).as("issues the console finds with q=%s (a word of %s)", word, issue.key())
                        .containsExactlyInAnyOrderElementsOf(holding);
                assertThat(found.json().path("totalElements").asLong(-1)).as("totalElements with q=%s", word)
                        .isEqualTo(holding.size());
                if (holding.size() < issues.size()) {
                    narrowed++;
                }
            }
        }
        assertThat(narrowed).as("search words that leave some issue of the sample out").isPositive();
    }

    @ParameterizedTest(name = "from {0}")
    @ValueSource(strings = {"0.1.0", "0.2.0"})
    @DisplayName("S3-AC-89 after the upgrade the portal list finds the issues the sample's customer sees by a word "
            + "of their title, and no issue without that word")
    void sampleIssuesAreFoundOnThePortal(String version) {
        Stack stack = UPGRADED.get(version).get();
        List<SampleIssue> issues = sampleIssues(version, admin(stack));
        ApiClient portal = stack.portal();
        portal.post("/api/public/sign-in").json(Map.of("email", SAMPLE_CUSTOMER, "password", SAMPLE_PASSWORD))
                .expect(204);
        List<String> seen = keys(portal.get("/api/portal/issues").query("size", 100).expect(200));
        List<SampleIssue> visible = issues.stream().filter(i -> seen.contains(i.key())).toList();
        assertThat(visible).as("issues of the sample that %s sees on the portal (it sees %s)", SAMPLE_CUSTOMER, seen)
                .isNotEmpty();
        int narrowed = 0;

        for (SampleIssue issue : visible) {
            String word = longestWord(issue.title());
            assertThat(word).as("a word of %d letters or more in the title of %s: %s", WORD_LENGTH, issue.key(),
                    issue.title()).isNotNull();
            List<String> holding = visible.stream().filter(i -> i.holds(word)).map(SampleIssue::key).toList();

            ApiResponse found = portal.get("/api/portal/issues").query("q", word).query("size", 100).expect(200);

            assertThat(keys(found)).as("issues the portal finds with q=%s (a word of %s)", word, issue.key())
                    .containsExactlyInAnyOrderElementsOf(holding);
            assertThat(found.json().path("totalElements").asLong(-1)).as("totalElements with q=%s", word)
                    .isEqualTo(holding.size());
            if (holding.size() < visible.size()) {
                narrowed++;
            }
        }
        assertThat(narrowed).as("search words that leave some issue the customer sees out").isPositive();
    }
}
