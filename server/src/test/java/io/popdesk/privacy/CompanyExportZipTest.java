package io.popdesk.privacy;

import static io.popdesk.privacy.CompanyExportSteps.COMMENT_FIELDS;
import static io.popdesk.privacy.CompanyExportSteps.ISSUE_FIELDS;
import static io.popdesk.privacy.CompanyExportSteps.MANIFEST_FIELDS;
import static io.popdesk.privacy.CompanyExportSteps.MISSING_FIELDS;
import static io.popdesk.privacy.CompanyExportSteps.NOTE_FIELDS;
import static io.popdesk.privacy.CompanyExportSteps.PERSON_FIELDS;
import static io.popdesk.privacy.CompanyExportSteps.TASK_FIELDS;
import static io.popdesk.privacy.CompanyExportSteps.fieldNames;
import static io.popdesk.privacy.CompanyExportSteps.integers;
import static io.popdesk.privacy.CompanyExportSteps.only;
import static io.popdesk.privacy.CompanyExportSteps.storedFile;
import static io.popdesk.privacy.CompanyExportSteps.texts;
import static io.popdesk.privacy.CompanyExportSteps.zipOf;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.attachment.ImageSteps;
import io.popdesk.issue.IssueFlow.OnBehalf;
import io.popdesk.issue.TalkFlow;
import io.popdesk.privacy.CompanyExportSteps.Zip;
import io.popdesk.support.Fixtures.Attachment;
import io.popdesk.support.Fixtures.Comment;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.File;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.NewIssue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import io.popdesk.task.TaskSteps;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/**
 * What the ZIP holds and how (S10B-AC-11 to S10B-AC-15): its paths and {@code manifest.json}, {@code issue.json} in
 * the fields a customer sees, comments and the internal part, the files, and {@code customers.json}. The company's
 * other side is {@link CompanyExportBoundaryTest}.
 */
class CompanyExportZipTest extends CompanyExportAppTest {

    private static final String SUPPORT_TEAM = "SUPPORT_TEAM";

    private static final String DUE_DATE = "2099-06-01";

    /** A company, its admin, a customer who reports, and an agent assigned to it. */
    private record Cast(Session admin, Company company, Session reporter, Session agent) {

        String code() {
            return company.code();
        }
    }

    private Cast cast() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        return new Cast(admin, company, fixtures.customer(admin, company.code()), fixtures.agent(admin, company.code()));
    }

    private static File file(String name) {
        return File.text(name, "Content of " + name + " " + Unique.number());
    }

    private static void deleteAttachment(Session session, String key, String id) {
        session.client().delete(session.api("/issues/" + key + "/attachments/" + id)).expect(204);
    }

    /** The person of the ZIP for a customer. */
    private static void assertCustomer(JsonNode person, Session customer, String name) {
        assertThat(fieldNames(person)).as("fields of the person %s", person)
                .containsExactlyInAnyOrderElementsOf(PERSON_FIELDS);
        assertThat(person.path("kind").asString(null)).as("kind of %s", person).isEqualTo("CUSTOMER");
        assertThat(person.path("accountId").asLong(-1)).as("accountId of %s", person).isEqualTo(customer.accountId());
        assertThat(person.path("name").asString(null)).as("name of %s", person).isEqualTo(name);
    }

    /** The person of the ZIP for staff: the support team, without an ID or a name. */
    private static void assertSupportTeam(JsonNode person) {
        assertThat(fieldNames(person)).as("fields of the person %s", person)
                .containsExactlyInAnyOrderElementsOf(PERSON_FIELDS);
        assertThat(person.path("kind").asString(null)).as("kind of %s", person).isEqualTo(SUPPORT_TEAM);
        assertThat(person.path("accountId").isNull() && person.path("name").isNull())
                .as("accountId and name are null in %s", person).isTrue();
    }

    /** Neither the staff member's name, email, nor account ID is anywhere in the JSON. */
    private static void assertNoTraceOf(JsonNode json, Session staff, String where) {
        assertThat(texts(json)).as("texts of %s", where).noneMatch(t -> t.contains(staff.name())
                || t.contains(staff.email()));
        assertThat(integers(json)).as("numbers of %s", where).doesNotContain(staff.accountId());
    }

    private static boolean isTime(String text) {
        try {
            OffsetDateTime.parse(text);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    @Test
    @DisplayName("S10B-AC-11 the ZIP holds manifest.json, customers.json, and per issue issue.json, comments.json, "
            + "and files/{attachment ID}-{file name}, nothing else; manifest.json has exactly code, name, exportedAt, "
            + "both choices, the counts issues, comments, attachments, and customers, and an empty missingFiles")
    void pathsAndManifest() {
        Cast c = cast();
        // A second member customer: customers.json counts 2.
        fixtures.customer(c.admin(), c.code());
        Issue issue = fixtures.fileIssue(c.reporter(), NewIssue.in(c.code()).file(file("first.txt"))
                .file(file("second.txt")));
        Comment first = fixtures.comment(c.reporter(), issue.key(), Unique.text("First"));
        fixtures.comment(c.agent(), issue.key(), Unique.text("Second"));
        fixtures.comment(c.reporter(), issue.key(), Unique.text("Third"));
        fixtures.attachToComment(c.reporter(), issue.key(), first.id(), file("third.txt"));
        fixtures.attach(c.agent(), issue.key(), file("fourth.txt"));
        List<JsonNode> attachments = TalkFlow.items(c.admin().client()
                .get("/api/console/issues/" + issue.key() + "/attachments").query("size", 100).expect(200));
        String made = clock.instant().toString();

        Zip zip = zipOf(c.admin(), c.code(), false, false);

        Set<String> expected = new HashSet<>(Set.of("manifest.json", "customers.json",
                "issues/" + issue.key() + "/issue.json", "issues/" + issue.key() + "/comments.json"));
        attachments.forEach(a -> expected.add("issues/" + issue.key() + "/files/" + a.path("id").asString("")
                + "-" + a.path("fileName").asString("")));
        assertThat(attachments).as("attachments of %s", issue.key()).hasSize(4);
        assertThat(zip.names()).as("paths in the ZIP").doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(expected);
        JsonNode manifest = zip.manifest();
        assertThat(manifest.path("code").asString(null)).as("code in %s", manifest).isEqualTo(c.code());
        assertThat(manifest.path("name").asString(null)).as("name in %s", manifest).isEqualTo(c.company().name());
        assertThat(manifest.path("includeInternal").isBoolean() && !manifest.path("includeInternal").asBoolean())
                .as("includeInternal false in %s", manifest).isTrue();
        assertThat(manifest.path("includePrivate").isBoolean() && !manifest.path("includePrivate").asBoolean())
                .as("includePrivate false in %s", manifest).isTrue();
        assertThat(fieldNames(manifest)).as("fields of %s", manifest)
                .containsExactlyInAnyOrderElementsOf(MANIFEST_FIELDS);
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String count : List.of("issues", "comments", "attachments", "customers")) {
            assertThat(manifest.path(count).isIntegralNumber()).as("%s is a whole number in %s", count, manifest)
                    .isTrue();
            counts.put(count, manifest.path(count).asLong());
        }
        assertThat(counts).as("counts in %s", manifest)
                .containsExactlyInAnyOrderEntriesOf(Map.of("issues", 1L, "comments", 3L, "attachments", 4L,
                        "customers", 2L));
        assertThat(integers(manifest)).as("numbers in %s: the four counts only", manifest)
                .containsExactlyInAnyOrder(1L, 2L, 3L, 4L);
        String exportedAt = manifest.path("exportedAt").asString("");
        assertThat(isTime(exportedAt) && OffsetDateTime.parse(exportedAt).toInstant().toString().equals(made))
                .as("exportedAt '%s' is %s", exportedAt, made).isTrue();
        assertThat(manifest.path("missingFiles").isArray() && manifest.path("missingFiles").isEmpty())
                .as("missingFiles is an empty array in %s", manifest).isTrue();
    }

    @Test
    @DisplayName("S10B-AC-11 in files/{attachment ID}-{file name}, '/', '\\', control characters, and a leading dot "
            + "become '_', so no path holds '..', a backslash, or starts with '/'; two files of the same name are two "
            + "entries with their own bytes")
    void fileNamesAreMadeSafe() {
        Cast c = cast();
        Issue issue = fixtures.fileIssue(c.reporter(), c.code());
        Map<String, String> expectedName = new LinkedHashMap<>();
        Map<String, Attachment> byId = new LinkedHashMap<>();
        for (String name : List.of("../x.txt", "a\\b.txt", ".hidden", "c\u0001d.txt", "same.txt", "same.txt")) {
            Attachment a = fixtures.attach(c.reporter(), issue.key(), file(name));
            String stored = a.json().path("fileName").asString("");
            byId.put(a.id(), a);
            if (!stored.startsWith("..")) {
                expectedName.put(a.id(), stored.replaceAll("[/\\\\\\p{Cntrl}]", "_").replaceFirst("^\\.", "_"));
            }
        }

        Zip zip = zipOf(c.admin(), c.code(), false, false);

        List<String> files = zip.filesOf(issue.key());
        assertThat(zip.names()).as("paths in the ZIP").allSatisfy(p -> assertThat(p)
                .doesNotContain("..").doesNotContain("\\").doesNotStartWith("/").doesNotMatch(".*\\p{Cntrl}.*"));
        assertThat(files).as("files of %s", issue.key()).hasSize(byId.size());
        byId.forEach((id, a) -> {
            String prefix = "issues/" + issue.key() + "/files/" + id + "-";
            List<String> of = files.stream().filter(p -> p.startsWith(prefix)).toList();
            assertThat(of).as("entries of attachment %s (%s)", id, a.json().path("fileName")).hasSize(1);
            String name = of.getFirst().substring(prefix.length());
            assertThat(name).as("file name of %s", of.getFirst()).doesNotStartWith(".").doesNotContain("/");
            if (expectedName.containsKey(id)) {
                assertThat(name).as("file name of %s", of.getFirst()).isEqualTo(expectedName.get(id));
            }
            assertThat(zip.bytes(of.getFirst())).as("bytes of %s", of.getFirst()).isEqualTo(a.content());
        });
    }

    @Test
    @DisplayName("S10B-AC-12 issue.json has exactly the twelve fields a customer sees (no companyCode), with the "
            + "portal's values, and the reporter as {kind, accountId, name}; no assignee, label, due date, or agent "
            + "who filed it on behalf")
    void issueJsonHoldsWhatTheCustomerSees() {
        Cast c = cast();
        Issue own = fixtures.fileIssue(c.reporter(), NewIssue.in(c.code()).priority("HIGH"));
        Issue onBehalf = flow.fileOnBehalf(c.agent(), OnBehalf.of(c.code(), c.reporter().accountId()));
        flow.assign(c.agent(), own.key(), c.agent().accountId());
        flow.assign(c.agent(), onBehalf.key(), c.agent().accountId());
        flow.moveTo(c.agent(), own.key(), "RESOLVED");

        Zip zip = zipOf(c.admin(), c.code(), false, false);

        for (Issue issue : List.of(own, onBehalf)) {
            JsonNode inZip = zip.issue(issue.key());
            JsonNode portal = flow.detail(c.reporter(), issue.key());
            List<String> fields = fieldNames(inZip);
            assertThat(fields).as("fields of issue.json of %s, exactly those of S10B-AC-12", issue.key())
                    .containsExactlyInAnyOrderElementsOf(ISSUE_FIELDS);
            for (String field : ISSUE_FIELDS) {
                if (field.endsWith("At")) {
                    assertThat(ExportSteps.time(inZip, field)).as("%s of %s, as the portal gives it", field,
                            issue.key()).isEqualTo(ExportSteps.time(portal, field));
                } else if (!field.equals("reporter")) {
                    assertThat(inZip.path(field)).as("%s of %s, as the portal gives it", field, issue.key())
                            .isEqualTo(portal.path(field));
                }
            }
            assertCustomer(inZip.path("reporter"), c.reporter(), c.reporter().name());
            assertNoTraceOf(inZip, c.agent(), "issue.json of " + issue.key());
        }
        assertThat(zip.issue(onBehalf.key()).path("filedBySupport").asBoolean(false)).as("filedBySupport").isTrue();
        assertThat(zip.issue(own.key()).path("status").asString(null)).as("status").isEqualTo("RESOLVED");
    }

    @ParameterizedTest(name = "includeInternal {0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("S10B-AC-13 comments.json is {comments, tasks}: the public comments that are not deleted, oldest "
            + "first, each {author, content, createdAt, editedAt, internal}; internal comments and tasks {title, "
            + "status, dueDate, internal, notes} only with includeInternal, marked internal; staff are always the "
            + "support team")
    void commentsAndTheInternalPart(boolean includeInternal) {
        Cast c = cast();
        Issue issue = fixtures.fileIssue(c.reporter(), c.code());
        String byCustomer = Unique.text("Public of the customer");
        String byAgent = Unique.text("Public of the agent");
        String internal = Unique.text("Internal of the agent");
        String deleted = Unique.text("Deleted");
        String taskTitle = Unique.text("Task");
        String note = Unique.text("Note");
        // A minute between the comments, so "oldest first" is an order of the times.
        fixtures.comment(c.reporter(), issue.key(), byCustomer);
        clock.advance(Duration.ofMinutes(1));
        fixtures.comment(c.agent(), issue.key(), byAgent);
        clock.advance(Duration.ofMinutes(1));
        fixtures.internalComment(c.agent(), issue.key(), internal);
        clock.advance(Duration.ofMinutes(1));
        TalkFlow.delete(c.reporter(), issue.key(), fixtures.comment(c.reporter(), issue.key(), deleted).id());
        long taskId = TaskSteps.id(TaskSteps.create(c.agent(), issue.key(),
                TaskSteps.body("title", taskTitle, "dueDate", DUE_DATE)));
        TaskSteps.note(c.agent(), issue.key(), taskId, note);
        JsonNode taskInConsole = TaskSteps.task(c.agent(), issue.key(), taskId);

        Zip zip = zipOf(c.admin(), c.code(), includeInternal, false);

        JsonNode root = zip.comments(issue.key());
        assertThat(fieldNames(root)).as("fields of comments.json").containsExactlyInAnyOrder("comments", "tasks");
        JsonNode comments = root.path("comments");
        JsonNode tasks = root.path("tasks");
        assertThat(comments.isArray() && tasks.isArray()).as("comments and tasks are arrays in %s", root).isTrue();
        List<String> texts = texts(root);
        assertThat(texts).as("texts of comments.json").doesNotContain(deleted);
        List<String> expectedOrder = includeInternal ? List.of(byCustomer, byAgent, internal)
                : List.of(byCustomer, byAgent);
        assertThat(comments.values()).extracting(n -> n.path("content").asString(null))
                .as("contents of comments, oldest first").containsExactlyElementsOf(expectedOrder);
        for (JsonNode comment : comments.values()) {
            assertThat(fieldNames(comment)).as("fields of %s", comment)
                    .containsExactlyInAnyOrderElementsOf(COMMENT_FIELDS);
            assertThat(comment.path("internal").isBoolean()).as("internal is a boolean in %s", comment).isTrue();
        }
        JsonNode ofCustomer = only(comments, "content", byCustomer);
        JsonNode ofAgent = only(comments, "content", byAgent);
        assertCustomer(ofCustomer.path("author"), c.reporter(), c.reporter().name());
        assertSupportTeam(ofAgent.path("author"));
        for (JsonNode comment : List.of(ofCustomer, ofAgent)) {
            assertThat(comment.path("internal").asBoolean(true)).as("internal of the public %s", comment).isFalse();
        }
        if (includeInternal) {
            JsonNode ofInternal = only(comments, "content", internal);
            assertSupportTeam(ofInternal.path("author"));
            assertThat(ofInternal.path("internal").asBoolean(false)).as("internal of %s", ofInternal).isTrue();
            assertThat(tasks.values()).as("tasks of %s", issue.key()).hasSize(1);
            JsonNode task = tasks.get(0);
            assertThat(fieldNames(task)).as("fields of the task %s", task)
                    .containsExactlyInAnyOrderElementsOf(TASK_FIELDS);
            assertThat(task.path("title").asString(null)).as("title of %s", task).isEqualTo(taskTitle);
            assertThat(task.path("status")).as("status of %s, as the console gives it", task)
                    .isEqualTo(taskInConsole.path("status"));
            assertThat(task.path("dueDate").asString(null)).as("dueDate of %s", task).isEqualTo(DUE_DATE);
            assertThat(task.path("internal").isBoolean() && task.path("internal").asBoolean())
                    .as("internal true in %s", task).isTrue();
            JsonNode notes = task.path("notes");
            assertThat(notes.isArray()).as("notes is an array in %s", task).isTrue();
            for (JsonNode n : notes.values()) {
                assertThat(fieldNames(n)).as("fields of the note %s", n)
                        .containsExactlyInAnyOrderElementsOf(NOTE_FIELDS);
            }
            JsonNode ofNote = only(notes, "content", note);
            assertSupportTeam(ofNote.path("author"));
        } else {
            assertThat(tasks.isEmpty()).as("tasks without includeInternal: %s", tasks).isTrue();
            assertThat(texts).as("texts of comments.json without the internal part").doesNotContain(internal,
                    taskTitle, note);
        }
        assertNoTraceOf(root, c.agent(), "comments.json");
    }

    @ParameterizedTest(name = "includeInternal {0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("S10B-AC-14 the ZIP holds the files of the issue and of its comments in the ZIP, content images "
            + "included, an internal comment's only with includeInternal, no deleted one; a file the storage cannot "
            + "read is left out and listed in manifest.json, and the export is DONE")
    void filesOfTheIssueAndItsComments(boolean includeInternal) {
        Cast c = cast();
        File onIssue = file("on-issue.txt");
        Issue issue = fixtures.fileIssue(c.reporter(), NewIssue.in(c.code()).file(onIssue));
        Comment pub = fixtures.comment(c.reporter(), issue.key(), Unique.text("With a file"));
        Attachment ofPublic = fixtures.attachToComment(c.reporter(), issue.key(), pub.id(), file("of-public.txt"));
        byte[] image = ImageSteps.png();
        String imageId = ImageSteps.attachedByComment(c.reporter(), issue.key(), image);
        Comment inside = fixtures.internalComment(c.agent(), issue.key(), Unique.text("Internal with a file"));
        Attachment ofInternal = fixtures.attachToComment(c.agent(), issue.key(), inside.id(), file("of-internal.txt"));
        Attachment gone = fixtures.attach(c.reporter(), issue.key(), file("deleted.txt"));
        deleteAttachment(c.reporter(), issue.key(), gone.id());
        Attachment unreadable = fixtures.attach(c.reporter(), issue.key(), file("unreadable.txt"));
        Path stored = storedFile(unreadable.id());
        assertThat(stored).as("the stored file of %s on disk", unreadable.id()).exists();
        try {
            Files.delete(stored);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String onIssueId = TalkFlow.items(c.admin().client()
                .get("/api/console/issues/" + issue.key() + "/attachments").query("size", 100).expect(200)).stream()
                .filter(a -> onIssue.name().equals(a.path("fileName").asString(null)))
                .map(a -> a.path("id").asString("")).findFirst().orElseThrow();

        Zip zip = zipOf(c.admin(), c.code(), includeInternal, false);

        Map<String, byte[]> expected = new LinkedHashMap<>();
        expected.put(onIssueId, onIssue.content());
        expected.put(ofPublic.id(), ofPublic.content());
        expected.put(imageId, image);
        if (includeInternal) {
            expected.put(ofInternal.id(), ofInternal.content());
        }
        List<String> files = zip.filesOf(issue.key());
        assertThat(files).as("files of %s", issue.key()).hasSize(expected.size());
        expected.forEach((id, bytes) -> {
            List<String> of = files.stream().filter(p -> p.startsWith("issues/" + issue.key() + "/files/" + id + "-"))
                    .toList();
            assertThat(of).as("entries of attachment %s", id).hasSize(1);
            assertThat(zip.bytes(of.getFirst())).as("bytes of %s", of.getFirst()).isEqualTo(bytes);
        });
        JsonNode manifest = zip.manifest();
        JsonNode missing = manifest.path("missingFiles");
        assertThat(missing.isArray()).as("missingFiles is an array in %s", manifest).isTrue();
        assertThat(missing.values()).as("missingFiles of %s", manifest).singleElement().satisfies(m -> {
            assertThat(fieldNames(m)).as("fields of %s", m).containsExactlyInAnyOrderElementsOf(MISSING_FIELDS);
            assertThat(m.path("attachmentId").isString()).as("attachmentId is text in %s", m).isTrue();
            assertThat(m.path("attachmentId").asString(null)).as("attachmentId of %s", m).isEqualTo(unreadable.id());
            assertThat(m.path("fileName").asString(null)).as("fileName of %s", m).isEqualTo("unreadable.txt");
        });
        assertThat(texts(manifest)).as("texts of %s", manifest).doesNotContain(gone.id(), ofPublic.id());
        if (!includeInternal) {
            assertThat(zip.allText()).as("the ZIP without the internal part").doesNotContain(ofInternal.id());
        }
    }

    @Test
    @DisplayName("S10B-AC-14 a storage error other than a missing file (a folder where the stored file is, so reading "
            + "it fails) ends the export FAILED with STORAGE and leaves no ZIP, instead of a DONE ZIP without the file")
    void storageErrorFailsTheExport() {
        Cast c = cast();
        Issue issue = fixtures.fileIssue(c.reporter(), c.code());
        Attachment broken = fixtures.attach(c.reporter(), issue.key(), file("broken.txt"));
        Path stored = storedFile(broken.id());
        assertThat(stored).as("the stored file of %s on disk", broken.id()).exists();
        JsonNode ended;
        try {
            Files.delete(stored);
            Files.createDirectory(stored);
            String id = CompanyExportSteps.id(CompanyExportSteps.start(c.admin(), c.code(), false, false));
            ended = CompanyExportSteps.awaitEnded(c.admin(), c.code(), id);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not put a folder where " + stored + " is", e);
        } finally {
            try {
                Files.deleteIfExists(stored);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        String id = CompanyExportSteps.id(ended);

        assertThat(CompanyExportSteps.state(ended)).as("state of %s", ended).isEqualTo("FAILED");
        assertThat(ended.path("failure").asString(null)).as("failure of %s", ended).isEqualTo("STORAGE");
        assertThat(TalkFlow.isNull(ended, "size")).as("size of %s is null", ended).isTrue();
        assertThat(CompanyExportSteps.exportFiles()).as("files in the export folder").noneMatch(n -> n.contains(id));
        assertThat(c.admin().client().get(CompanyExportSteps.filePath(id)).send().status())
                .as("download of the failed export").isEqualTo(404);
    }

    @Test
    @DisplayName("S10B-AC-15 customers.json holds the company's members now, each with email and name only, also one "
            + "of two companies, a suspended one, and an anonymized one with the account's anonymous values (also as "
            + "the reporter); not a former member, staff, or another company's customer")
    void customersAsTheyAreNow() {
        Cast c = cast();
        Company other = fixtures.createCompany(c.admin());
        Session ofTwo = fixtures.customer(c.admin(), c.code(), other.code());
        Session suspended = fixtures.customer(c.admin(), c.code());
        Session anonymized = fixtures.customer(c.admin(), c.code());
        Session former = fixtures.customer(c.admin(), c.code());
        Session ofOther = fixtures.customer(c.admin(), other.code());
        Issue byAnonymized = fixtures.fileIssue(anonymized, c.code());
        flow.suspend(c.admin(), suspended.accountId());
        flow.removeCustomer(c.admin(), c.code(), former.accountId());
        PrivacySteps.anonymize(c.admin(), anonymized.accountId());
        String anonymousName = PrivacySteps.anonymizedAccount(c.admin(), anonymized.accountId()).path("name")
                .asString(null);

        Zip zip = zipOf(c.admin(), c.code(), false, false);

        JsonNode customers = zip.customers();
        assertThat(customers.isArray()).as("customers.json is an array: %s", customers).isTrue();
        List<JsonNode> people = List.copyOf(customers.values());
        people.forEach(p -> assertThat(fieldNames(p)).as("fields of %s", p).containsExactlyInAnyOrder("email",
                "name"));
        assertThat(people).extracting(p -> p.path("email").asString(null) + " / " + p.path("name").asString(null))
                .as("members in customers.json").containsExactlyInAnyOrder(
                        c.reporter().email() + " / " + c.reporter().name(),
                        ofTwo.email() + " / " + ofTwo.name(),
                        suspended.email() + " / " + suspended.name(),
                        PrivacySteps.anonymousEmail(anonymized.accountId()) + " / " + anonymousName);
        String text = new String(zip.bytes("customers.json"), StandardCharsets.UTF_8);
        assertThat(text).as("customers.json").doesNotContain(other.code(), ofOther.email(), former.email(),
                c.agent().email(), anonymized.email());
        assertCustomer(zip.issue(byAnonymized.key()).path("reporter"), anonymized, anonymousName);
    }
}
