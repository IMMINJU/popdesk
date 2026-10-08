package io.popdesk.issue;

import io.popdesk.support.ApiResponse;
import io.popdesk.support.Fixtures;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.TestClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Steps and JSON reading shared by the issue and comment tests. Only HTTP, through {@link Fixtures}.
 *
 * <p>Sign-in is limited to 20 attempts per IP address in 60 seconds (S0-AC-24), every test client
 * comes from 127.0.0.1, and the test clock stands still. {@link #admin()} moves the clock past that
 * window before it signs the admin in, so a long run of test classes in one context does not reach
 * the limit.
 */
final class IssueSteps {

    private static final Duration SIGN_IN_WINDOW = Duration.ofSeconds(61);

    private final Fixtures fixtures;
    private final TestClock clock;

    IssueSteps(Fixtures fixtures, TestClock clock) {
        this.fixtures = fixtures;
        this.clock = clock;
    }

    /** The first admin, signed in on the console. */
    Session admin() {
        clock.advance(SIGN_IN_WINDOW);
        return fixtures.signInAdmin();
    }

    /** A new company and one signed-in customer of it. */
    Scene scene() {
        Session admin = admin();
        Company company = fixtures.createCompany(admin);
        Session customer = fixtures.customer(admin, company.code());
        return new Scene(admin, company, customer);
    }

    /** An admin, a company, and its first customer. */
    record Scene(Session admin, Company company, Session customer) {

        String code() {
            return company.code();
        }
    }

    /** Moves the app clock so that the next row gets a later time than the last one. */
    Instant tick() {
        return clock.advance(Duration.ofSeconds(1));
    }

    // JSON

    /** The {@code items} of a page body. */
    static List<JsonNode> items(ApiResponse page) {
        JsonNode items = page.json().path("items");
        if (!items.isArray()) {
            throw new AssertionError(page.request() + ": no items array in " + page.snippet());
        }
        return new ArrayList<>(items.values());
    }

    /** A text field, or null when it is missing, null, or not text. */
    static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() ? value.stringValue() : null;
    }

    static List<String> texts(List<JsonNode> nodes, String field) {
        return nodes.stream().map(n -> text(n, field)).toList();
    }

    /** Keys of the issues on a page, in page order. */
    static List<String> keys(ApiResponse page) {
        return texts(items(page), "key");
    }

    /** {@code field} of each entry of {@code fields} in a 400 body. */
    static List<String> fieldNames(ApiResponse error) {
        JsonNode fields = error.json().path("fields");
        if (!fields.isArray()) {
            throw new AssertionError(error.request() + ": no fields array in " + error.snippet());
        }
        return texts(new ArrayList<>(fields.values()), "field");
    }

    /** The {@code code} that {@code fields} gives for {@code field}, or null. */
    static String fieldCode(ApiResponse error, String field) {
        JsonNode fields = error.json().path("fields");
        for (JsonNode f : fields.values()) {
            if (field.equals(text(f, "field"))) {
                return text(f, "code");
            }
        }
        return null;
    }

    /** The number after the last {@code -} of an issue key ({@code C0001-7} gives 7). */
    static long numberOf(String key) {
        int dash = key == null ? -1 : key.lastIndexOf('-');
        if (dash < 0) {
            throw new AssertionError("Not an issue key: " + key);
        }
        try {
            return Long.parseLong(key.substring(dash + 1));
        } catch (NumberFormatException e) {
            throw new AssertionError("Not an issue key: " + key, e);
        }
    }
}
