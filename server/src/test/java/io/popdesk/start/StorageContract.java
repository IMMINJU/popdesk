package io.popdesk.start;

import static io.popdesk.start.StorageSteps.MB;
import static io.popdesk.start.StorageSteps.bytes;
import static io.popdesk.start.StorageSteps.delete;
import static io.popdesk.start.StorageSteps.download;
import static io.popdesk.start.StorageSteps.entry;
import static io.popdesk.start.StorageSteps.fileIssue;
import static io.popdesk.start.StorageSteps.ids;
import static io.popdesk.start.StorageSteps.list;
import static io.popdesk.start.StorageSteps.path;
import static io.popdesk.start.StorageSteps.scene;
import static io.popdesk.start.StorageSteps.sha256;
import static io.popdesk.start.StorageSteps.slice;
import static io.popdesk.start.StorageSteps.small;
import static io.popdesk.start.StorageSteps.state;
import static io.popdesk.start.StorageSteps.upload;
import static io.popdesk.start.StorageSteps.uploaded;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.attachment.ImageSteps;
import io.popdesk.start.StorageSteps.Scene;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.Unique;
import io.popdesk.support.Wait;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/**
 * What holds for {@code disk} and {@code s3} alike, on one running app of each
 * ({@link DiskStorageTest}, {@link S3StorageTest}): the requests answer as with {@code db}, paths and
 * keys are made of the attachment ID only, the list does not read the storage, a file that is gone
 * is a 500 on download and counts as removed on delete.
 */
abstract class StorageContract extends OwnStartTest {

    /** One running app and a look into its storage. */
    interface Store {

        Boot app();

        Database db();

        /** {@code disk} or {@code s3}. */
        String kind();

        /** Whether the storage holds the file of this attachment, where design.md says it is. */
        boolean holds(String id);

        /** Removes the file from the storage, past the app. */
        void lose(String id);

        /** Every path under the folder, or every key in the bucket. */
        List<String> paths();

        /** Paths or keys that are neither the marker nor the place of an attachment ID, and files next to the folder. */
        List<String> strays();
    }

    /** The class's app; made by the first test that asks. */
    abstract Store store();

    @ParameterizedTest(name = "file name {0}")
    @ValueSource(strings = {"../../%s.txt", "..\\..\\%s", "%s 공백 과 한글 이름.html"})
    @DisplayName("S5B-AC-03 a hostile or unusual file name is kept on the row only: no path or key holds a piece of "
            + "it, the file is where its ID says, and nothing appears outside the storage's place")
    void namesNeverReachThePath(String pattern) {
        Store store = store();
        Scene s = scene(store.app());
        String key = fileIssue(s.customer(), s.code());
        String token = "esc" + Unique.number() + "ape";
        String name = String.format(pattern, token);

        ApiResponse created = s.customer().client().post(s.customer().api("/issues/" + key + "/attachments"))
                .file("file", name, "text/html", small()).send();

        assertThat(created.status()).as("%s %s", created, created.snippet()).isEqualTo(201);
        String id = created.jsonText("id");
        assertThat(store.holds(id)).as("the file of %s at the place its ID gives; all paths: %s", id, store.paths())
                .isTrue();
        assertThat(store.paths()).as("paths and keys of the storage")
                .noneMatch(p -> p.contains(token) || p.contains("한글") || p.contains("html") || p.contains(".."));
        assertThat(store.strays()).as("paths that are neither the marker nor made of an attachment ID").isEmpty();
    }

    @Test
    @DisplayName("S5B-AC-04 attachments and temporary images answer as with db: upload, upload with an issue, upload "
            + "to a comment, list, download by hash, ranges across the 1 MB pieces, HEAD, delete, 404 and the trace "
            + "afterwards, an image uploaded, read and attached, and the limits for an empty and a too large file")
    void answersAsWithDb() {
        Store store = store();
        Scene s = scene(store.app());

        // Filed with a file.
        byte[] filedWith = small();
        String filedName = Unique.text("filed-with") + ".txt";
        ApiResponse filed = s.customer().client().post("/api/portal/issues")
                .jsonPart("issue", Map.of("companyCode", s.code(), "title", Unique.text("With a file"), "content",
                        "Filed with a file."))
                .file("files", filedName, "text/plain", filedWith).expect(201);
        String key = filed.jsonText("key");
        List<JsonNode> afterFiling = list(s.customer(), key);
        assertThat(afterFiling).as("attachments of the new issue").hasSize(1);
        String filedId = afterFiling.getFirst().path("id").asString(null);
        assertThat(afterFiling.getFirst().path("fileName").asString(null)).isEqualTo(filedName);
        assertThat(download(s.customer(), key, filedId).expectStatus(200).body()).isEqualTo(filedWith);
        assertThat(store.holds(filedId)).as("the file sent with the issue is in %s", store.kind()).isTrue();

        // A file over two piece boundaries.
        byte[] content = bytes(2 * MB + 300_017, Unique.number());
        String name = Unique.text("large") + ".bin";
        ApiResponse created = upload(s.customer(), key, name, content);
        assertThat(created.status()).as("%s %s", created, created.snippet()).isEqualTo(201);
        String id = created.jsonText("id");
        assertThat(ImageSteps.UUID_FORM.matcher(id).matches()).as("id %s", id).isTrue();
        assertThat(created.jsonText("fileName")).isEqualTo(name);
        assertThat(created.jsonLong("size")).isEqualTo(content.length);
        assertThat(created.json().path("inline").asBoolean(true)).as("inline").isFalse();
        assertThat(created.json().path("deleted").asBoolean(true)).as("deleted").isFalse();

        // On a comment.
        long commentId = StorageSteps.comment(s.customer(), key);
        byte[] onComment = small();
        ApiResponse commentFile = s.customer().client().post(s.customer().api("/issues/" + key + "/attachments"))
                .file("file", "on-comment.bin", "application/octet-stream", onComment)
                .part("commentId", Long.toString(commentId)).expect(201);
        assertThat(commentFile.jsonLong("commentId")).isEqualTo(commentId);

        // List.
        JsonNode listed = entry(s.customer(), key, id);
        assertThat(ids(list(s.customer(), key))).contains(filedId, id, commentFile.jsonText("id"));
        assertThat(listed.path("fileName").asString(null)).isEqualTo(name);
        assertThat(listed.path("size").asLong()).isEqualTo(content.length);

        // Download, ranges, HEAD.
        ApiResponse whole = download(s.customer(), key, id);
        assertThat(whole.status()).as(whole.request()).isEqualTo(200);
        assertThat(whole.sha256()).isEqualTo(sha256(content));
        assertThat(whole.header("Content-Length")).isEqualTo(Integer.toString(content.length));
        assertThat(whole.header("Content-Disposition")).startsWith("attachment");
        assertThat(whole.header("X-Content-Type-Options")).isEqualTo("nosniff");

        int from = MB - 1_000;
        int to = 2 * MB + 999;
        ApiResponse middle = s.customer().client().get(path(s.customer(), key, id))
                .header("Range", "bytes=" + from + "-" + to).send();
        assertThat(middle.status()).as(middle.request()).isEqualTo(206);
        assertThat(middle.header("Content-Range")).isEqualTo("bytes " + from + "-" + to + "/" + content.length);
        assertThat(middle.body()).isEqualTo(slice(content, from, to));

        ApiResponse end = s.customer().client().get(path(s.customer(), key, id)).header("Range", "bytes=-17").send();
        assertThat(end.status()).as(end.request()).isEqualTo(206);
        assertThat(end.header("Content-Range"))
                .isEqualTo("bytes " + (content.length - 17) + "-" + (content.length - 1) + "/" + content.length);
        assertThat(end.body()).isEqualTo(slice(content, content.length - 17, content.length - 1));

        ApiResponse head = s.customer().client().head(path(s.customer(), key, id)).send();
        assertThat(head.status()).as(head.request()).isEqualTo(200);
        assertThat(head.header("Content-Length")).isEqualTo(Integer.toString(content.length));
        assertThat(head.body()).isEmpty();

        // Delete, and what is left.
        assertThat(store.holds(id)).as("the file before the delete").isTrue();
        ApiResponse deleted = delete(s.customer(), key, id);
        assertThat(deleted.status()).as("%s %s", deleted, deleted.snippet()).isEqualTo(204);
        ApiResponse gone = download(s.customer(), key, id);
        assertThat(gone.status()).isEqualTo(404);
        assertThat(gone.errorCode()).isEqualTo("NOT_FOUND");
        JsonNode trace = entry(s.customer(), key, id);
        assertThat(trace).as("the trace of the deleted attachment in the list").isNotNull();
        assertThat(trace.path("fileName").isNull()).as("fileName of the trace is null").isTrue();
        assertThat(trace.path("deleted").asBoolean(false)).as("deleted of the trace").isTrue();
        assertThat(state(store.db(), id)).isEqualTo("purged");
        assertThat(store.holds(id)).as("the file after the delete").isFalse();

        // A temporary image: uploaded, read, attached by a comment.
        byte[] image = ImageSteps.png();
        String imageId = ImageSteps.uploaded(s.customer(), image);
        assertThat(store.holds(imageId)).as("the temporary image is in %s", store.kind()).isTrue();
        ApiResponse read = ImageSteps.read(s.customer(), imageId);
        assertThat(read.status()).as(read.request()).isEqualTo(200);
        assertThat(read.header("Content-Type")).startsWith("image/png");
        assertThat(read.body()).isEqualTo(image);
        ImageSteps.commentWith(s.customer(), key, Unique.text("With an image"), List.of(imageId));
        assertThat(ImageSteps.inlineIds(s.customer(), key)).contains(imageId);
        assertThat(download(s.customer(), key, imageId).expectStatus(200).body()).isEqualTo(image);

        // Limits.
        ApiResponse empty = upload(s.customer(), key, "empty.bin", new byte[0]);
        assertThat(empty.status()).isEqualTo(400);
        assertThat(empty.errorCode()).isEqualTo("FILE_EMPTY");
        String overName = Unique.text("over") + ".bin";
        ApiResponse over = upload(s.customer(), key, overName, bytes(10 * MB + 1, 5));
        assertThat(over.status()).isEqualTo(400);
        assertThat(over.errorCode()).isEqualTo("FILE_TOO_LARGE");
        assertThat(list(s.customer(), key)).as("attachments after the refused uploads")
                .noneMatch(n -> overName.equals(n.path("fileName").asString(null)));
        assertThat(store.strays()).as("paths that are neither the marker nor made of an attachment ID").isEmpty();
    }

    @Test
    @DisplayName("S5B-AC-05 the list does not read the storage: with the file removed from the storage, the list "
            + "still answers 200 with the attachment's name and size")
    void listDoesNotReadTheStorage() {
        Store store = store();
        Scene s = scene(store.app());
        String key = fileIssue(s.customer(), s.code());
        byte[] content = small();
        String name = Unique.text("listed") + ".bin";
        String id = uploaded(s.customer(), key, name, content);
        assertThat(store.holds(id)).as("the file of %s in %s", id, store.kind()).isTrue();

        store.lose(id);

        JsonNode listed = entry(s.customer(), key, id);
        assertThat(listed).as("the list entry of %s", id).isNotNull();
        assertThat(listed.path("fileName").asString(null)).isEqualTo(name);
        assertThat(listed.path("size").asLong()).isEqualTo(content.length);
    }

    @Test
    @DisplayName("S5B-AC-31 a stored attachment whose file is gone from the storage is 500 INTERNAL_ERROR on "
            + "download and on a range request, never 200; the log names the attachment ID and the storage kind "
            + "and not the file name")
    void missingFileIsAnError() {
        Store store = store();
        Scene s = scene(store.app());
        String key = fileIssue(s.customer(), s.code());
        String token = "lost" + Unique.number() + "name";
        String id = uploaded(s.customer(), key, token + ".bin", bytes(MB + 4_321, Unique.number()));
        assertThat(download(s.customer(), key, id).status()).as("download while the file is there").isEqualTo(200);
        store.lose(id);
        int mark = Console.mark();

        ApiResponse whole = download(s.customer(), key, id);
        ApiResponse part = s.customer().client().get(path(s.customer(), key, id)).header("Range", "bytes=10-99")
                .send();

        assertThat(whole.status()).as("%s %s", whole, whole.snippet()).isEqualTo(500);
        assertThat(whole.errorCode()).isEqualTo("INTERNAL_ERROR");
        assertThat(part.status()).as("%s %s", part, part.snippet()).isEqualTo(500);
        assertThat(part.errorCode()).isEqualTo("INTERNAL_ERROR");
        List<String> lines = Wait.until("a log line with the attachment ID " + id, Duration.ofSeconds(10), () -> {
            List<String> found = Console.linesSince(mark).stream().filter(line -> line.contains(id)).toList();
            return found.isEmpty() ? null : found;
        });
        assertThat(lines).as("log lines with the attachment ID").anyMatch(line -> line.contains(store.kind()));
        assertThat(Console.since(mark)).as("the log since the downloads").doesNotContain(token);
        assertThat(state(store.db(), id)).as("the row stays stored").isEqualTo("stored");
    }

    @Test
    @DisplayName("S5B-AC-33 deleting an attachment whose file is already gone from the storage is 204 and the row "
            + "ends purged")
    void deletingAFileThatIsGone() {
        Store store = store();
        Scene s = scene(store.app());
        String key = fileIssue(s.customer(), s.code());
        String id = uploaded(s.customer(), key, "already-gone.bin", small());
        assertThat(store.holds(id)).as("the file of %s in %s", id, store.kind()).isTrue();
        store.lose(id);

        ApiResponse deleted = delete(s.customer(), key, id);

        assertThat(deleted.status()).as("%s %s", deleted, deleted.snippet()).isEqualTo(204);
        assertThat(state(store.db(), id)).isEqualTo("purged");
        assertThat(entry(s.customer(), key, id).path("fileName").isNull()).as("fileName of the trace is null")
                .isTrue();
        assertThat(download(s.customer(), key, id).status()).isEqualTo(404);
    }
}
