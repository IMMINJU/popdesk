package io.popdesk.attachment;

import io.popdesk.support.ApiResponse;
import io.popdesk.support.Fixtures;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Session;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Random;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;

/**
 * Steps and JSON reading shared by the attachment tests. Only HTTP, through {@link Fixtures}.
 *
 * <p>Sizes: this package reads "10 MB" and "50 MB" in the criteria as 10 MiB and 50 MiB (Spring's
 * {@code DataSize}, as {@code POPDESK_ATTACHMENT_QUOTA=10GB} is written). The report asks the main
 * session to confirm this.
 */
final class AttachmentSteps {

    static final int MB = 1024 * 1024;

    /** The largest file (ATT-2). */
    static final int MAX_FILE = 10 * MB;

    /** The largest sum of public attachments on one issue (ATT-2). */
    static final long MAX_PUBLIC_PER_ISSUE = 50L * MB;

    private final Fixtures fixtures;

    AttachmentSteps(Fixtures fixtures) {
        this.fixtures = fixtures;
    }

    /** A new company and one signed-in customer of it. */
    Scene scene() {
        Session admin = fixtures.signInAdmin();
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

    // Requests

    /** {@code POST /api/{app}/issues/{key}/attachments}, whatever the answer. */
    static ApiResponse upload(Session session, String key, String fileName, byte[] content) {
        return session.client().post(session.api("/issues/" + key + "/attachments"))
                .file("file", fileName, "application/octet-stream", content)
                .send();
    }

    /** The same, onto the caller's comment. */
    static ApiResponse uploadToComment(Session session, String key, long commentId, String fileName, byte[] content) {
        return session.client().post(session.api("/issues/" + key + "/attachments"))
                .file("file", fileName, "application/octet-stream", content)
                .part("commentId", Long.toString(commentId))
                .send();
    }

    static String path(Session session, String key, String id) {
        return session.api("/issues/" + key + "/attachments/" + id);
    }

    static ApiResponse download(Session session, String key, String id) {
        return session.client().get(path(session, key, id)).send();
    }

    /** Every attachment the caller sees on the issue (one page of 100). */
    static List<JsonNode> list(Session session, String key) {
        ApiResponse page = session.client().get(session.api("/issues/" + key + "/attachments"))
                .query("size", 100)
                .expect(200);
        JsonNode items = page.json().path("items");
        if (!items.isArray()) {
            throw new AssertionError(page.request() + ": no items array in " + page.snippet());
        }
        return new ArrayList<>(items.values());
    }

    /** The list entry with this ID, or null. */
    static JsonNode entry(Session session, String key, String id) {
        return list(session, key).stream().filter(n -> id.equals(text(n, "id"))).findFirst().orElse(null);
    }

    static List<String> ids(List<JsonNode> items) {
        return items.stream().map(n -> text(n, "id")).toList();
    }

    // JSON

    /** A text field, or null when it is missing, null, or not text. */
    static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() ? value.stringValue() : null;
    }

    /** The {@code code} that {@code fields} of a 400 body gives for {@code field}, or null. */
    static String fieldCode(ApiResponse error, String field) {
        for (JsonNode f : error.json().path("fields").values()) {
            if (field.equals(text(f, "field"))) {
                return text(f, "code");
            }
        }
        return null;
    }

    // Content

    /** {@code size} bytes that are the same for the same seed. */
    static byte[] bytes(int size, long seed) {
        byte[] out = new byte[size];
        new Random(seed).nextBytes(out);
        return out;
    }

    static byte[] text(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }

    static byte[] slice(byte[] content, int from, int toInclusive) {
        return Arrays.copyOfRange(content, from, toInclusive + 1);
    }

    /** Headers of a response by lower-case name, without {@code Date}, the one header that may differ by time. */
    static Map<String, List<String>> headersWithoutDate(ApiResponse response) {
        Map<String, List<String>> out = new TreeMap<>();
        response.headers().forEach((name, values) -> {
            if (!name.equalsIgnoreCase("date")) {
                out.put(name.toLowerCase(Locale.ROOT), values);
            }
        });
        return out;
    }
}
