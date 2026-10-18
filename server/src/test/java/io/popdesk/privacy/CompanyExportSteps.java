package io.popdesk.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiRequest;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Wait;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Steps of the company export (S10B-AC-01 to S10B-AC-17, conventions.md "Company export (PII-8)"): asking for one,
 * the list, waiting for the end, the file as a {@link Zip}, and looking through the JSON of the ZIP by values a test
 * made itself. Every failure is an {@link AssertionError}.
 *
 * <p>The contract names the paths of the ZIP and the fields of {@code issue.json}, the person, {@code customers.json},
 * and of {@code manifest.json} only {@code code}, {@code name}, {@code includeInternal}, and {@code includePrivate}.
 * The other parts (the counts, the missing list, how {@code comments.json} holds comments and tasks) are found by the
 * values in them ({@link #objectsHolding}, {@link #integers}), not by a field name.
 */
final class CompanyExportSteps {

    /** The table of the exports (design.md 1절), for the tests that look at rows. */
    static final String TABLE = "company_export";

    /** How long an export of a few issues may take. */
    static final Duration EXPORT_TIME = Duration.ofSeconds(60);

    static final List<String> EXPORT_FIELDS = List.of("id", "state", "failure", "includeInternal", "includePrivate",
            "requestedBy", "requestedAt", "finishedAt", "size");

    /** The fields of {@code issue.json} acceptance.md S10B-AC-12 names. */
    static final List<String> ISSUE_FIELDS = List.of("key", "title", "content", "status", "priority", "private",
            "reporter", "filedBySupport", "createdAt", "updatedAt", "resolvedAt", "closedAt");

    /** The person in the ZIP: the portal's {@code Person} without the photo (acceptance.md 5절 20). */
    static final List<String> PERSON_FIELDS = List.of("kind", "accountId", "name");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private CompanyExportSteps() {}

    static String exportsPath(String code) {
        return "/api/console/companies/" + code + "/exports";
    }

    static String filePath(String id) {
        return "/api/console/company-exports/" + id + "/file";
    }

    static Map<String, Object> body(boolean includeInternal, boolean includePrivate) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("includeInternal", includeInternal);
        body.put("includePrivate", includePrivate);
        return body;
    }

    /** {@code POST .../exports} with both choices, not sent yet. */
    static ApiRequest createRequest(Session caller, String code, boolean includeInternal, boolean includePrivate) {
        return caller.client().post(exportsPath(code)).json(body(includeInternal, includePrivate));
    }

    /**
     * Asks for an export and expects 202; gives the answer. While another class's export still runs (409
     * {@code EXPORT_RUNNING}) it asks again, so a test does not depend on the one before it.
     */
    static JsonNode start(Session admin, String code, boolean includeInternal, boolean includePrivate) {
        ApiResponse answer = Wait.until("an export of " + code + " that is not refused as EXPORT_RUNNING",
                EXPORT_TIME, () -> {
                    ApiResponse r = createRequest(admin, code, includeInternal, includePrivate).send();
                    return r.status() == 409 && "EXPORT_RUNNING".equals(r.errorCode()) ? null : r;
                });
        return answer.expectStatus(202).json();
    }

    /** The company's exports as the list gives them. */
    static List<JsonNode> list(Session admin, String code) {
        JsonNode body = admin.client().get(exportsPath(code)).expect(200).json();
        assertThat(body.isArray()).as("the export list of %s is an array: %s", code, body).isTrue();
        List<JsonNode> out = new ArrayList<>();
        body.forEach(out::add);
        return out;
    }

    /** The export with this ID in the company's list; fails unless there is one. */
    static JsonNode listed(Session admin, String code, String id) {
        List<JsonNode> found = list(admin, code).stream().filter(e -> id.equals(e.path("id").asString(null)))
                .toList();
        assertThat(found).as("exports of %s with ID %s", code, id).hasSize(1);
        return found.getFirst();
    }

    static String state(JsonNode export) {
        return export.path("state").asString(null);
    }

    static String id(JsonNode export) {
        String id = export.path("id").asString(null);
        assertThat(id).as("id of the export %s", export).isNotBlank();
        return id;
    }

    /** Reads the list again until the export is no longer {@code RUNNING}; gives it as listed then. */
    static JsonNode awaitEnded(Session admin, String code, String id) {
        return Wait.until("the export " + id + " of " + code + " ended", EXPORT_TIME, () -> {
            JsonNode e = listed(admin, code, id);
            return "RUNNING".equals(state(e)) ? null : e;
        });
    }

    /** Asks for an export, waits for its end, and expects {@code DONE}; gives it as listed then. */
    static JsonNode done(Session admin, String code, boolean includeInternal, boolean includePrivate) {
        String id = id(start(admin, code, includeInternal, includePrivate));
        JsonNode ended = awaitEnded(admin, code, id);
        assertThat(state(ended)).as("state of the ended export %s", ended).isEqualTo("DONE");
        return ended;
    }

    /** Downloads the export's file and reads it as a ZIP. */
    static Zip download(Session admin, String id) {
        ApiResponse file = admin.client().get(filePath(id)).expect(200);
        return Zip.of(file.body());
    }

    /** An export of the company, done and downloaded. */
    static Zip zipOf(Session admin, String code, boolean includeInternal, boolean includePrivate) {
        return download(admin, id(done(admin, code, includeInternal, includePrivate)));
    }

    // The folders

    /** The names of the files in the export folder now. */
    static List<String> exportFiles() {
        try (Stream<Path> files = Files.list(CompanyExportAppTest.EXPORT_DIR)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not list " + CompanyExportAppTest.EXPORT_DIR, e);
        }
    }

    /**
     * Runs {@code work} while the export folder is a file, so no ZIP can be written there: the folder is moved
     * aside with the files in it and put back afterwards, also when {@code work} fails.
     */
    static void withExportDirAFile(Runnable work) {
        Path dir = CompanyExportAppTest.EXPORT_DIR;
        Path aside = dir.resolveSibling(dir.getFileName() + "-aside");
        try {
            Files.move(dir, aside);
            Files.writeString(dir, "Not a folder: the company export tests made this file.");
        } catch (IOException e) {
            throw new UncheckedIOException("Could not put a file where the export folder is", e);
        }
        try {
            work.run();
        } finally {
            try {
                Files.deleteIfExists(dir);
                Files.move(aside, dir);
            } catch (IOException e) {
                throw new UncheckedIOException("Could not put the export folder back", e);
            }
        }
    }

    /** Where the app stores an attachment on {@code disk}: {@code {folder}/{first two characters}/{ID}}. */
    static Path storedFile(String attachmentId) {
        return CompanyExportAppTest.STORAGE_DIR.resolve(attachmentId.substring(0, 2)).resolve(attachmentId);
    }

    // The JSON of a ZIP

    static JsonNode parse(byte[] content, String what) {
        try {
            return JSON.readTree(content);
        } catch (JacksonException e) {
            throw new AssertionError(what + " is not JSON: " + new String(content, StandardCharsets.UTF_8), e);
        }
    }

    /** Every object in the tree, the root among them. */
    static List<JsonNode> objects(JsonNode root) {
        List<JsonNode> out = new ArrayList<>();
        collect(root, JsonNode::isObject, out);
        return out;
    }

    /** Every object in the tree that has a field with this text value. */
    static List<JsonNode> objectsHolding(JsonNode root, String value) {
        return objects(root).stream().filter(o -> o.properties().stream()
                .anyMatch(f -> f.getValue().isString() && value.equals(f.getValue().stringValue()))).toList();
    }

    /** The one object in the tree with this text in {@code field}; fails unless there is one. */
    static JsonNode only(JsonNode root, String field, String value) {
        List<JsonNode> found = objects(root).stream().filter(o -> value.equals(o.path(field).asString(null)))
                .toList();
        assertThat(found).as("objects with %s '%s' in %s", field, value, root).hasSize(1);
        return found.getFirst();
    }

    /** Every whole number in the tree, booleans not counted. */
    static List<Long> integers(JsonNode root) {
        List<JsonNode> found = new ArrayList<>();
        collect(root, JsonNode::isIntegralNumber, found);
        return found.stream().map(JsonNode::asLong).toList();
    }

    /** Every text value and every field name in the tree. */
    static List<String> texts(JsonNode root) {
        List<String> out = new ArrayList<>();
        collectTexts(root, out);
        return out;
    }

    private static void collect(JsonNode node, Predicate<JsonNode> which, List<JsonNode> out) {
        if (which.test(node)) {
            out.add(node);
        }
        if (node.isObject()) {
            node.properties().forEach(f -> collect(f.getValue(), which, out));
        } else if (node.isArray()) {
            node.forEach(n -> collect(n, which, out));
        }
    }

    private static void collectTexts(JsonNode node, List<String> out) {
        if (node.isString()) {
            out.add(node.stringValue());
        } else if (node.isObject()) {
            node.properties().forEach(f -> {
                out.add(f.getKey());
                collectTexts(f.getValue(), out);
            });
        } else if (node.isArray()) {
            node.forEach(n -> collectTexts(n, out));
        }
    }

    static List<String> fieldNames(JsonNode object) {
        assertThat(object != null && object.isObject()).as("an object: %s", object).isTrue();
        return new ArrayList<>(object.propertyNames());
    }

    /** A ZIP read whole: its entry names in the order of the file, and the bytes of each. */
    record Zip(List<String> names, Map<String, byte[]> files) {

        static Zip of(byte[] content) {
            List<String> names = new ArrayList<>();
            Map<String, byte[]> files = new LinkedHashMap<>();
            try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(content), StandardCharsets.UTF_8)) {
                for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                    names.add(entry.getName());
                    files.put(entry.getName(), in.readAllBytes());
                }
            } catch (IOException e) {
                throw new AssertionError("The file is not a ZIP (" + content.length + " bytes)", e);
            }
            assertThat(names).as("entries of the ZIP").isNotEmpty();
            return new Zip(List.copyOf(names), files);
        }

        boolean has(String path) {
            return files.containsKey(path);
        }

        byte[] bytes(String path) {
            assertThat(files).as("entries of the ZIP").containsKey(path);
            return files.get(path);
        }

        JsonNode json(String path) {
            return parse(bytes(path), path);
        }

        JsonNode manifest() {
            return json("manifest.json");
        }

        JsonNode customers() {
            return json("customers.json");
        }

        JsonNode issue(String key) {
            return json("issues/" + key + "/issue.json");
        }

        JsonNode comments(String key) {
            return json("issues/" + key + "/comments.json");
        }

        /** The paths under {@code issues/{key}/files/}. */
        List<String> filesOf(String key) {
            String prefix = "issues/" + key + "/files/";
            return names.stream().filter(n -> n.startsWith(prefix)).toList();
        }

        /** The keys that have an {@code issue.json}. */
        List<String> issueKeys() {
            return names.stream().filter(n -> n.startsWith("issues/") && n.endsWith("/issue.json"))
                    .map(n -> n.substring("issues/".length(), n.length() - "/issue.json".length())).toList();
        }

        /** The whole ZIP as text: every path and the content of every JSON entry. */
        String allText() {
            StringBuilder out = new StringBuilder();
            names.forEach(n -> out.append(n).append('\n'));
            files.forEach((n, b) -> {
                if (n.endsWith(".json")) {
                    out.append(new String(b, StandardCharsets.UTF_8)).append('\n');
                }
            });
            return out.toString();
        }
    }
}
