package io.popdesk.project;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.DbInspector;
import io.popdesk.support.DbInspector.Column;
import io.popdesk.support.Fixtures.Session;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * Helpers for the company and invitation tests: reading whole lists page by page, error bodies,
 * and looking through the whole database for a value without knowing table names (the tests do
 * not know the schema the implementation picks).
 */
final class ProjectChecks {

    private static final Set<String> TEXT_TYPES = Set.of("text", "character varying", "character", "USER-DEFINED");

    private ProjectChecks() {}

    // Lists

    /** Every item of a COM-4 list, read page by page with {@code size=100}. */
    static List<JsonNode> allItems(ApiClient client, String path) {
        List<JsonNode> out = new ArrayList<>();
        for (int page = 1; page <= 1000; page++) {
            ApiResponse response = client.get(path).query("page", page).query("size", 100).expect(200);
            JsonNode body = response.json();
            JsonNode items = body.path("items");
            if (!items.isArray()) {
                throw new AssertionError(response.request() + ": no items array in " + response.snippet());
            }
            items.forEach(out::add);
            if (page >= body.path("totalPages").asInt(0)) {
                return out;
            }
        }
        throw new AssertionError(path + " has more than 1000 pages");
    }

    /** The values of one text field over every item of a list. */
    static List<String> allValues(ApiClient client, String path, String field) {
        return allItems(client, path).stream().map(n -> n.path(field).asString(null)).toList();
    }

    /** The account with this ID from {@code GET /api/console/accounts}, read by an admin. */
    static JsonNode account(Session admin, long accountId) {
        return allItems(admin.client(), "/api/console/accounts").stream()
                .filter(n -> n.path("id").asLong(-1) == accountId)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No account " + accountId + " in GET /api/console/accounts"));
    }

    /** The account's {@code status} as the admin sees it. */
    static String status(Session admin, long accountId) {
        return account(admin, accountId).path("status").asString(null);
    }

    /** The entries of the pending invitation list with this email. */
    static List<JsonNode> pending(Session admin, String email) {
        return allItems(admin.client(), "/api/console/invitations").stream()
                .filter(n -> email.equals(n.path("email").asString(null)))
                .toList();
    }

    // Error bodies

    /** Checks status, {@code code}, and that {@code fields} names {@code field} with {@code fieldCode}. */
    static void assertFieldError(ApiResponse response, int status, String field, String fieldCode) {
        assertThat(response.status()).as(response.request() + ": " + response.snippet()).isEqualTo(status);
        assertThat(response.errorCode()).isEqualTo("INVALID_INPUT");
        List<String> pairs = new ArrayList<>();
        response.json().path("fields").forEach(f -> pairs.add(f.path("field").asString(null) + ":"
                + f.path("code").asString(null)));
        assertThat(pairs).as("fields of " + response.request()).contains(field + ":" + fieldCode);
    }

    /** Checks status and {@code code}. */
    static void assertError(ApiResponse response, int status, String code) {
        assertThat(response.status()).as(response.request() + ": " + response.snippet()).isEqualTo(status);
        assertThat(response.errorCode()).isEqualTo(code);
    }

    /** Checks that {@code Cache-Control} has {@code no-store}. */
    static void assertNoStore(ApiResponse response) {
        assertThat(response.headers("Cache-Control"))
                .as("Cache-Control of " + response.request() + " (" + response.status() + ")")
                .anySatisfy(v -> assertThat(v.toLowerCase(Locale.ROOT)).contains("no-store"));
    }

    // Database, without knowing the schema

    /** Row count of every table. */
    static Map<String, Long> rowCounts(DbInspector db) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : db.tables()) {
            counts.put(table, db.count("select count(*) from " + quote(table)));
        }
        return counts;
    }

    /** Tables that have more rows in {@code after} than in {@code before}, sessions left out. */
    static Map<String, Long> grown(Map<String, Long> before, Map<String, Long> after) {
        Map<String, Long> grown = new LinkedHashMap<>();
        after.forEach((table, n) -> {
            long delta = n - before.getOrDefault(table, 0L);
            if (delta > 0 && !table.startsWith("spring_session")) {
                grown.put(table, delta);
            }
        });
        return grown;
    }

    /**
     * Every {@code table.column} whose value, written as text, contains one of {@code needles}.
     * Every column of every table is read; {@code bytea} reads as {@code \x} and lower-case hex.
     */
    static List<String> columnsContaining(DbInspector db, List<String> needles) {
        List<String> found = new ArrayList<>();
        for (String table : db.tables()) {
            for (Column column : db.columns(table)) {
                for (String needle : needles) {
                    long n = db.count("select count(*) from " + quote(table) + " where position(? in "
                            + quote(column.name()) + "::text) > 0", needle);
                    if (n > 0) {
                        found.add(table + "." + column.name() + " has " + needle);
                    }
                }
            }
        }
        return found;
    }

    /** Rows of any table that have a text column equal to {@code value}, ignoring case. */
    static List<Map<String, Object>> rowsWithText(DbInspector db, String value) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String table : db.tables()) {
            for (Column column : db.columns(table)) {
                if (TEXT_TYPES.contains(column.dataType())) {
                    rows.addAll(db.rows("select * from " + quote(table) + " where lower(" + quote(column.name())
                            + "::text) = lower(?)", value));
                }
            }
        }
        return rows;
    }

    private static String quote(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    // Tokens

    /** The 32 bytes a token stands for, read as base64url or hex, if it is either. */
    static Optional<byte[]> tokenBytes(String token) {
        if (token.matches("[0-9a-fA-F]{64}")) {
            return Optional.of(HexFormat.of().parseHex(token));
        }
        if (token.matches("[A-Za-z0-9_-]{43}=?")) {
            return Optional.of(Base64.getUrlDecoder().decode(token));
        }
        return Optional.empty();
    }

    /** Ways a token itself could be stored: as given, and its raw bytes as hex. */
    static List<String> tokenForms(String token) {
        List<String> forms = new ArrayList<>(List.of(token));
        tokenBytes(token).ifPresent(b -> forms.add(HexFormat.of().formatHex(b)));
        return forms;
    }

    /**
     * Ways the SHA-256 of a token could be stored: of the token text or of its raw bytes, as hex
     * (either case; {@code bytea} reads as lower-case hex) or base64 (standard or URL-safe, with or
     * without padding).
     */
    static List<String> hashForms(String token) {
        List<byte[]> inputs = new ArrayList<>(List.of(token.getBytes(StandardCharsets.UTF_8)));
        tokenBytes(token).ifPresent(inputs::add);
        List<String> forms = new ArrayList<>();
        for (byte[] input : inputs) {
            byte[] hash = sha256(input);
            String hex = HexFormat.of().formatHex(hash);
            forms.add(hex);
            forms.add(hex.toUpperCase(Locale.ROOT));
            forms.add(Base64.getEncoder().encodeToString(hash));
            forms.add(Base64.getEncoder().withoutPadding().encodeToString(hash));
            forms.add(Base64.getUrlEncoder().encodeToString(hash));
            forms.add(Base64.getUrlEncoder().withoutPadding().encodeToString(hash));
        }
        return forms.stream().distinct().toList();
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
