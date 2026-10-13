package io.popdesk.start;

import static io.popdesk.start.StorageSteps.MB;
import static io.popdesk.start.StorageSteps.again;
import static io.popdesk.start.StorageSteps.bytes;
import static io.popdesk.start.StorageSteps.contentRows;
import static io.popdesk.start.StorageSteps.delete;
import static io.popdesk.start.StorageSteps.download;
import static io.popdesk.start.StorageSteps.fileIssue;
import static io.popdesk.start.StorageSteps.keyOf;
import static io.popdesk.start.StorageSteps.list;
import static io.popdesk.start.StorageSteps.location;
import static io.popdesk.start.StorageSteps.locationOf;
import static io.popdesk.start.StorageSteps.markerKey;
import static io.popdesk.start.StorageSteps.path;
import static io.popdesk.start.StorageSteps.scene;
import static io.popdesk.start.StorageSteps.small;
import static io.popdesk.start.StorageSteps.state;
import static io.popdesk.start.StorageSteps.storage;
import static io.popdesk.start.StorageSteps.upload;
import static io.popdesk.start.StorageSteps.uploaded;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.attachment.ImageSteps;
import io.popdesk.start.StorageSteps.Gate;
import io.popdesk.start.StorageSteps.S3;
import io.popdesk.start.StorageSteps.Scene;
import io.popdesk.start.StorageSteps.Shared;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.TestClock;
import io.popdesk.support.Unique;
import io.popdesk.support.Wait;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * {@code POPDESK_STORAGE=s3}: one app on a new database and a new empty bucket of the S3 stand-in,
 * shared by the tests of this class and of {@link StorageContract}. The app reaches the stand-in
 * through a {@link Gate}, which a test closes to make the storage fail; every such test opens it
 * again before it ends. The app's clock is a {@link TestClock}: the cleanup runs when a test moves
 * it a day ahead, which ends every session, so those tests sign in again.
 */
class S3StorageTest extends StorageContract {

    private static final Duration PAST_A_DAY = Duration.ofHours(25);

    /** The cleanup wakes every second of real time; allow a few rounds. */
    private static final Duration CLEANUP = Duration.ofSeconds(40);

    private static final String ATTACHMENT_CLEANUP = "attachment-cleanup";

    /** The running app, its database, the stand-in, and its bucket. */
    record Bucket(Boot app, Database db, S3 s3, String bucket) implements Store {

        @Override
        public String kind() {
            return "s3";
        }

        @Override
        public boolean holds(String id) {
            return s3.has(bucket, keyOf(id));
        }

        @Override
        public void lose(String id) {
            s3.remove(bucket, keyOf(id));
        }

        @Override
        public List<String> paths() {
            return s3.keys(bucket);
        }

        @Override
        public List<String> strays() {
            return paths().stream().filter(k -> !k.equals(markerKey()))
                    .filter(k -> !k.startsWith(StorageSteps.PREFIX + "/") || !ImageSteps.UUID_FORM
                            .matcher(k.substring(StorageSteps.PREFIX.length() + 1)).matches())
                    .toList();
        }

        TestClock clock() {
            return StorageSteps.clock(app);
        }
    }

    private static final Shared<Bucket> RUNNING = new Shared<>("starting the app with POPDESK_STORAGE=s3", () -> {
        S3 s3 = S3.start();
        try {
            Database db = postgres.newDatabase();
            String bucket = s3.newBucket();
            Boot app = Boot.start(StorageSteps.withTestClock(StorageSteps.s3(db, s3, bucket)));
            app.assertStarted();
            return new Bucket(app, db, s3, bucket);
        } catch (AssertionError | RuntimeException e) {
            s3.close();
            throw e;
        }
    });

    @AfterAll
    static void stop() {
        Bucket running = RUNNING.peek();
        if (running != null) {
            running.app().close();
            running.s3().close();
        }
    }

    @Override
    Store store() {
        return RUNNING.get();
    }

    @Test
    @DisplayName("S5B-AC-02 with POPDESK_STORAGE=s3 a new file's content is the object popdesk/{ID} in S3_BUCKET, "
            + "not in the database, and its row says s3 and {bucket}/popdesk")
    void newFilesGoToTheBucket() {
        Bucket b = RUNNING.get();
        Scene s = scene(b.app());
        String key = fileIssue(s.customer(), s.code());
        byte[] content = bytes(70_000, Unique.number());

        String id = uploaded(s.customer(), key, "in-s3.bin", content);

        assertThat(b.s3().has(b.bucket(), keyOf(id))).as("the object %s; keys: %s", keyOf(id), b.paths()).isTrue();
        assertThat(b.s3().read(b.bucket(), keyOf(id))).as("content of %s", keyOf(id)).isEqualTo(content);
        assertThat(storage(b.db(), id)).as("storage of the row").isEqualTo("s3");
        assertThat(location(b.db(), id)).as("storage_location of the row").isEqualTo(locationOf(b.bucket()));
        assertThat(contentRows(b.db(), id)).as("rows of attachment_content for %s", id).isZero();
    }

    @Test
    @DisplayName("S5B-AC-06 with s3 the app itself sends the file: download is 200 and a range 206 with the bytes "
            + "as the body, no Location, and no answer holds the storage's address, the bucket, or a signed URL")
    void downloadsComeFromTheApp() {
        Bucket b = RUNNING.get();
        Scene s = scene(b.app());
        String key = fileIssue(s.customer(), s.code());
        byte[] content = bytes(MB + 50_000, Unique.number());
        ApiResponse created = upload(s.customer(), key, "through-the-app.bin", content).expectStatus(201);
        String id = created.jsonText("id");

        // ApiClient never follows a redirect (HttpClient.Redirect.NEVER), so a 3xx would show here.
        ApiResponse whole = download(s.customer(), key, id);
        ApiResponse part = s.customer().client().get(path(s.customer(), key, id)).header("Range", "bytes=5-104")
                .send();
        ApiResponse listed = s.customer().client().get(s.customer().api("/issues/" + key + "/attachments"))
                .expect(200);

        assertThat(whole.status()).as(whole.request()).isEqualTo(200);
        assertThat(whole.body()).isEqualTo(content);
        assertThat(part.status()).as(part.request()).isEqualTo(206);
        assertThat(part.body()).isEqualTo(StorageSteps.slice(content, 5, 104));
        for (ApiResponse answer : List.of(created, whole, part, listed)) {
            assertThat(answer.header("Location")).as("Location of %s", answer.request()).isNull();
            String headers = answer.headers().toString();
            String body = answer == whole || answer == part ? "" : answer.text();
            for (String told : List.of(headers, body)) {
                assertThat(told).as("headers and JSON body of %s", answer.request())
                        .doesNotContain(b.bucket())
                        .doesNotContain(":" + b.s3().gate().port())
                        .doesNotContainIgnoringCase("X-Amz-");
            }
        }
    }

    @Test
    @DisplayName("S5B-AC-20, S5B-AC-24 the first start on an empty bucket that no row points to starts, leaves the "
            + "marker popdesk/.popdesk-storage in the bucket, and logs an INFO line with s3 and {bucket}/popdesk; "
            + "what the start wrote to try the bucket is gone: the bucket holds the marker and attachment objects only")
    void firstStartLeavesTheMarker() {
        Bucket b = RUNNING.get();

        assertThat(b.strays()).as("keys that are neither the marker nor an attachment's").isEmpty();

        assertThat(b.s3().has(b.bucket(), markerKey())).as("the marker %s; keys: %s", markerKey(), b.paths())
                .isTrue();
        assertThat(StorageSteps.linesWith(b.app(), locationOf(b.bucket())))
                .as("log lines of the start that name %s", locationOf(b.bucket()))
                .anyMatch(line -> line.contains("INFO") && line.contains("s3"));
    }

    @Test
    @DisplayName("S5B-AC-26 with an http S3_ENDPOINT the app starts and logs a WARN line that names S3_ENDPOINT")
    void httpEndpointIsWarnedAbout() {
        Bucket b = RUNNING.get();

        assertThat(b.app().logLines()).as("WARN lines of the start that name S3_ENDPOINT")
                .anyMatch(line -> line.contains("WARN") && line.contains("S3_ENDPOINT"));
    }

    @Test
    @DisplayName("S5B-AC-25 a running s3 app has the values of S3_ACCESS_KEY and S3_SECRET_KEY nowhere in its log, "
            + "plain or percent-encoded, also after an upload and after an upload that failed, and not in the "
            + "failed upload's answer")
    void secretsStayOutOfLogAndAnswers() {
        Bucket b = RUNNING.get();
        Scene s = scene(b.app());
        String key = fileIssue(s.customer(), s.code());
        uploaded(s.customer(), key, "logged.bin", small());
        ApiResponse failed;
        b.s3().gate().mode(Gate.Mode.DROP);
        try {
            failed = upload(s.customer(), key, "not-stored.bin", small());
        } finally {
            b.s3().gate().mode(Gate.Mode.PASS);
        }

        assertThat(failed.status()).as("%s %s", failed, failed.snippet()).isEqualTo(500);
        StorageSteps.assertHoldsNoSecret(b.app().log(), "the app's log since its start", b.s3().accessKey(),
                b.s3().secretKey());
        assertThat(failed.text() + failed.headers()).as("the answer of the failed upload")
                .doesNotContain(b.s3().accessKey()).doesNotContain(b.s3().secretKey());
    }

    @Test
    @DisplayName("S5B-AC-30 while the storage cannot be reached an upload is 500 INTERNAL_ERROR and its file is not "
            + "in the list; when the storage is back, uploads work again")
    void failedWriteIsAnErrorAndNotListed() {
        Bucket b = RUNNING.get();
        Scene s = scene(b.app());
        String key = fileIssue(s.customer(), s.code());
        String name = Unique.text("unreachable") + ".bin";
        ApiResponse failed;
        b.s3().gate().mode(Gate.Mode.DROP);
        try {
            failed = upload(s.customer(), key, name, small());
        } finally {
            b.s3().gate().mode(Gate.Mode.PASS);
        }

        assertThat(failed.status()).as("%s %s", failed, failed.snippet()).isEqualTo(500);
        assertThat(failed.errorCode()).isEqualTo("INTERNAL_ERROR");
        assertThat(list(s.customer(), key)).as("attachments after the failed upload")
                .noneMatch(n -> name.equals(n.path("fileName").asString(null)));
        byte[] content = small();
        String id = uploaded(s.customer(), key, "after-recovery.bin", content);
        assertThat(download(s.customer(), key, id).expectStatus(200).body()).isEqualTo(content);
    }

    @Test
    @DisplayName("S5B-AC-34 when the storage takes the connection and never answers, an upload still ends, with 500 "
            + "INTERNAL_ERROR, within 90 seconds, and uploads work again afterwards")
    void aSilentStorageDoesNotHoldAnUploadForever() {
        Bucket b = RUNNING.get();
        Scene s = scene(b.app());
        String key = fileIssue(s.customer(), s.code());
        HttpResponse<String> answer;
        long started = System.nanoTime();
        b.s3().gate().mode(Gate.Mode.STALL);
        try {
            answer = StorageSteps.uploadWithin(Duration.ofSeconds(150), s.customer(), key, "silent.bin", small());
        } finally {
            b.s3().gate().mode(Gate.Mode.PASS);
        }
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(answer.statusCode()).as("status of the upload; body %s", answer.body()).isEqualTo(500);
        assertThat(answer.body()).contains("INTERNAL_ERROR");
        assertThat(took).as("how long the upload waited for the silent storage").isLessThan(Duration.ofSeconds(90));
        uploaded(s.customer(), key, "after-silence.bin", small());
    }

    @Test
    @DisplayName("S5B-AC-33 when removing the file fails, the delete still answers 204 and the row stays purging "
            + "with its object in the bucket; once the storage is back, the attachment cleanup removes the object "
            + "and the row ends purged")
    void failedRemovalIsFinishedByTheCleanup() {
        Bucket b = RUNNING.get();
        Scene s = scene(b.app());
        String key = fileIssue(s.customer(), s.code());
        String id = uploaded(s.customer(), key, "kept-for-now.bin", small());
        ApiResponse deleted;
        b.s3().gate().mode(Gate.Mode.DROP);
        try {
            deleted = delete(s.customer(), key, id);
        } finally {
            b.s3().gate().mode(Gate.Mode.PASS);
        }

        assertThat(deleted.status()).as("%s %s", deleted, deleted.snippet()).isEqualTo(204);
        assertThat(state(b.db(), id)).isEqualTo("purging");
        assertThat(b.holds(id)).as("the object of %s while the row is purging", id).isTrue();
        assertThat(download(s.customer(), key, id).status()).isEqualTo(404);

        b.clock().advance(PAST_A_DAY);

        Wait.untilAsserted("the cleanup removed the object and ended the row", CLEANUP, () -> {
            assertThat(state(b.db(), id)).isEqualTo("purged");
            assertThat(b.holds(id)).as("the object of %s", id).isFalse();
        });
    }

    @Test
    @DisplayName("S5B-AC-35 with many files to remove and a storage that cannot be reached, the attachment cleanup "
            + "stops its run at the first one it cannot reach, and the other cleanup jobs run on time")
    void anUnreachableStorageDoesNotHoldTheOtherJobs() {
        Bucket b = RUNNING.get();
        Scene s = scene(b.app());
        String key = fileIssue(s.customer(), s.code());
        List<String> purging = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            purging.add(uploaded(s.customer(), key, "to-remove-" + i + ".bin", small()));
        }
        b.s3().gate().mode(Gate.Mode.DROP);
        try {
            for (String id : purging) {
                delete(s.customer(), key, id).expectStatus(204);
            }
            assertThat(purging).as("rows left purging").allMatch(id -> state(b.db(), id).equals("purging"));
            int before = b.s3().gate().accepted();

            Instant due = b.clock().advance(PAST_A_DAY);
            Session admin = again(b.app(), s.admin());

            // Every other job has a run at or after the new time: the round got past the attachment cleanup.
            Wait.untilAsserted("every other cleanup job ran at or after " + due, Duration.ofSeconds(60), () -> {
                List<String> late = new ArrayList<>();
                int others = 0;
                for (JsonNode job : admin.client().get("/api/console/system/status").expect(200).json()
                        .path("cleanupJobs")) {
                    String name = job.path("name").asString("");
                    if (name.equals(ATTACHMENT_CLEANUP)) {
                        continue;
                    }
                    others++;
                    if (!job.path("lastRunAt").isString()
                            || OffsetDateTime.parse(job.path("lastRunAt").stringValue()).toInstant().isBefore(due)) {
                        late.add(name);
                    }
                }
                assertThat(others).as("cleanup jobs besides %s", ATTACHMENT_CLEANUP).isPositive();
                assertThat(late).as("cleanup jobs without a run at or after %s", due).isEmpty();
            });
            // One file is tried, with the client's own retries (the SDK's defaults open about ten
            // connections for one request that cannot connect), not each of the 24.
            Wait.holds("the cleanup asked the storage for one file, not for each of the 24", Duration.ofSeconds(5),
                    () -> b.s3().gate().accepted() - before < purging.size());
            assertThat(b.s3().gate().accepted() - before).as("connections the cleanup opened to the storage")
                    .isPositive();
            assertThat(purging).as("rows still purging").allMatch(id -> state(b.db(), id).equals("purging"));
        } finally {
            b.s3().gate().mode(Gate.Mode.PASS);
        }
    }
}
