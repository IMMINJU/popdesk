package io.popdesk.boundary;

import io.popdesk.support.ApiRequest;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.MailServer;
import io.popdesk.support.ReceivedMail;
import io.popdesk.support.Wait;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.assertj.core.api.SoftAssertions;
import tools.jackson.databind.JsonNode;

/**
 * Requests and searches shared by the boundary tests. Only HTTP, through the support package.
 *
 * <p>The comparison with an unknown attachment ID is the same as in the attachment package's
 * {@code AttachmentNotFoundTest}; this package keeps its own copy (report: candidate for support).
 */
final class BoundaryChecks {

    /** Pages read at most from one list, so a list that never ends fails instead of hanging. */
    private static final int MAX_PAGES = 50;

    private BoundaryChecks() {}

    // Lists

    /** Every page of a list (COM-4), 100 items a page: the raw bodies and the items. */
    record Pages(List<ApiResponse> responses, List<JsonNode> items) {

        /** All bodies as one text, for searching. */
        String text() {
            StringBuilder out = new StringBuilder();
            responses.forEach(r -> out.append(r.text()).append('\n'));
            return out.toString();
        }

        List<String> texts(String field) {
            return items.stream().map(n -> n.path(field)).filter(JsonNode::isString).map(JsonNode::stringValue)
                    .toList();
        }
    }

    /** Reads {@code path} page by page until the last page. Fails unless each page is 200 with {@code items}. */
    static Pages allPages(Session session, String path) {
        List<ApiResponse> responses = new ArrayList<>();
        List<JsonNode> items = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            ApiResponse response = session.client().get(path).query("page", page).query("size", 100).expect(200);
            JsonNode body = response.json();
            if (!body.path("items").isArray() || !body.path("totalPages").isIntegralNumber()) {
                throw new AssertionError(response.request() + ": no items or totalPages in " + response.snippet());
            }
            responses.add(response);
            items.addAll(body.path("items").values());
            if (page >= body.path("totalPages").asLong()) {
                return new Pages(responses, items);
            }
        }
        throw new AssertionError(path + " has more than " + MAX_PAGES + " pages of 100");
    }

    // Unknown attachment IDs

    static String attachmentPath(Session session, String key, String id) {
        return session.api("/issues/" + key + "/attachments/" + id);
    }

    /**
     * For download, range request, {@code HEAD}, and delete (in that order, so delete comes last),
     * {@code caller}'s request for {@code id} under {@code key} answers 404 with the same headers
     * (but {@code Date}) and the same body bytes as their request for a random ID under
     * {@code ownKey}, an issue they can see.
     */
    static void assertSameAsUnknownId(SoftAssertions softly, String who, Session caller, String ownKey, String key,
            String id) {
        String unknown = attachmentPath(caller, ownKey, UUID.randomUUID().toString());
        String target = attachmentPath(caller, key, id);
        compare(softly, who + ", download", caller, unknown, target, p -> caller.client().get(p));
        compare(softly, who + ", range", caller, unknown, target,
                p -> caller.client().get(p).header("Range", "bytes=0-9"));
        compare(softly, who + ", HEAD", caller, unknown, target, p -> caller.client().head(p));
        compare(softly, who + ", delete", caller, unknown, target, p -> caller.client().delete(p));
    }

    private static void compare(SoftAssertions softly, String what, Session caller, String unknown, String target,
            Function<String, ApiRequest> request) {
        ApiResponse expected = request.apply(unknown).send();
        ApiResponse actual = request.apply(target).send();
        softly.assertThat(expected.status()).as(what + " of an unknown ID: " + expected.request()).isEqualTo(404);
        softly.assertThat(actual.status()).as(what + " status: " + actual.request()).isEqualTo(expected.status());
        softly.assertThat(headersWithoutDate(actual)).as(what + " headers").isEqualTo(headersWithoutDate(expected));
        softly.assertThat(actual.body()).as(what + " body").isEqualTo(expected.body());
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

    // Account IDs in JSON

    /**
     * Keys whose numbers count something or name a comment, never a person: paging, versions, file
     * sizes, and comment IDs. A staff account ID may equal one of these by chance.
     */
    private static final Set<String> NOT_ACCOUNTS = Set.of("page", "size", "totalElements", "totalPages", "version",
            "commentId");

    /**
     * Where {@code json} holds {@code accountId}: a number equal to it under any key but the
     * counting ones and an item's own {@code id} next to a {@code content} or {@code fileName}
     * (a comment or an attachment), or a text equal to its digits. JSON paths like
     * {@code items[0].author.accountId}.
     */
    static List<String> placesOfAccountId(JsonNode json, long accountId) {
        List<String> out = new ArrayList<>();
        walk(json, "$", null, accountId, out);
        return out;
    }

    private static void walk(JsonNode node, String path, JsonNode parent, long accountId, List<String> out) {
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                walk(e.getValue(), path + "." + e.getKey(), node, accountId, out);
            }
        } else if (node.isArray()) {
            int i = 0;
            for (JsonNode child : node.values()) {
                walk(child, path + "[" + i++ + "]", node, accountId, out);
            }
        } else if (node.isIntegralNumber() && node.asLong() == accountId) {
            String key = path.substring(path.lastIndexOf('.') + 1);
            boolean itemId = key.equals("id") && parent != null
                    && (parent.has("content") || parent.has("fileName"));
            if (!NOT_ACCOUNTS.contains(key) && !itemId) {
                out.add(path);
            }
        } else if (node.isString() && node.stringValue().strip().equals(Long.toString(accountId))) {
            out.add(path);
        }
    }

    // Mail

    /** Mail to the address other than invitations. */
    static List<ReceivedMail> notifications(MailServer mail, String address) {
        return mail.messagesTo(address).stream().filter(m -> !m.text().contains("/invite#token=")).toList();
    }

    /** Waits until {@code address} has at least {@code n} mails other than invitations. */
    static List<ReceivedMail> awaitNotifications(MailServer mail, String address, int n) {
        return Wait.until(n + " notification mail(s) to " + address, () -> {
            List<ReceivedMail> found = notifications(mail, address);
            return found.size() >= n ? found : null;
        });
    }

    /** Everything a person could read in a mail: headers, subject, decoded text, raw source. */
    static String everything(ReceivedMail m) {
        return m.headers() + "\n" + m.subject() + "\n" + m.text() + "\n" + m.raw();
    }

    /** Headers that carry the transport's own numbers (dates, message IDs, MIME parameters), not content. */
    private static final Set<String> TRANSPORT_HEADERS = Set.of("date", "received", "message-id", "return-path",
            "mime-version", "content-type", "content-transfer-encoding");

    private static final Pattern URL = Pattern.compile("https?://[^\\s\"'<>()]+");

    /** A date written with an English month name: {@code Oct 8, 2026}, {@code 8 October 2026}. */
    private static final Pattern DATE = Pattern.compile("(?i)\\b(?:\\d{1,2}\\s+)?(?:jan|feb|mar|apr|may|jun|jul|aug"
            + "|sep|oct|nov|dec)[a-z]*\\.?(?:\\s+\\d{1,2})?(?:,?\\s+\\d{4})?\\b");

    /**
     * Where a mail shows {@code accountId} as a number of its own: a path segment or query value of a
     * link, or a number standing alone in the subject, the text (HTML tags, styles, character
     * references, and dates with a month name taken out), or a header other than the transport ones.
     * Digits next to a letter, a digit, one of {@code : . - /}, or a Korean date or time unit belong
     * to something else (a time, a date, an issue key) and are not counted.
     */
    static List<String> placesOfAccountId(ReceivedMail m, long accountId) {
        String id = Long.toString(accountId);
        List<String> out = new ArrayList<>();
        String text = m.text();
        Matcher links = URL.matcher(text + "\n" + m.raw());
        while (links.find()) {
            for (String part : links.group().replaceFirst("^https?://[^/]+", "").split("[/?&=#;]")) {
                if (part.equals(id)) {
                    out.add("link " + links.group());
                }
            }
        }
        Pattern alone = Pattern.compile("(?<![0-9A-Za-z:./-])" + id + "(?![0-9A-Za-z:./\\-년월일시분초])");
        String plain = DATE.matcher(URL.matcher(text).replaceAll(" ")
                .replaceAll("(?is)<style.*?</style>", " ")
                .replaceAll("(?s)<[^>]*>", " ")
                .replaceAll("&#?\\w+;", " ")).replaceAll(" ");
        if (alone.matcher(m.subject() == null ? "" : m.subject()).find()) {
            out.add("subject: " + m.subject());
        }
        Matcher inText = alone.matcher(plain);
        while (inText.find()) {
            int from = Math.max(0, inText.start() - 30);
            int to = Math.min(plain.length(), inText.end() + 30);
            out.add("text: ..." + plain.substring(from, to) + "...");
        }
        m.headers().forEach((name, values) -> {
            if (!TRANSPORT_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                for (String value : values) {
                    if (alone.matcher(value).find()) {
                        out.add("header " + name + ": " + value);
                    }
                }
            }
        });
        return out;
    }

    // Text

    /** {@code n} written in small letters only ({@code 0 -> a}, {@code 27 -> bb}), for values that must hold no digits. */
    static String letters(long n) {
        StringBuilder out = new StringBuilder();
        long v = n;
        do {
            out.insert(0, (char) ('a' + v % 26));
            v /= 26;
        } while (v > 0);
        return out.toString();
    }
}
