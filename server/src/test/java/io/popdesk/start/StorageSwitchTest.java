package io.popdesk.start;

import static io.popdesk.start.StorageSteps.MB;
import static io.popdesk.start.StorageSteps.again;
import static io.popdesk.start.StorageSteps.bytes;
import static io.popdesk.start.StorageSteps.contentRows;
import static io.popdesk.start.StorageSteps.delete;
import static io.popdesk.start.StorageSteps.download;
import static io.popdesk.start.StorageSteps.fileIssue;
import static io.popdesk.start.StorageSteps.fileOf;
import static io.popdesk.start.StorageSteps.keyOf;
import static io.popdesk.start.StorageSteps.location;
import static io.popdesk.start.StorageSteps.locationOf;
import static io.popdesk.start.StorageSteps.scene;
import static io.popdesk.start.StorageSteps.sha256;
import static io.popdesk.start.StorageSteps.small;
import static io.popdesk.start.StorageSteps.state;
import static io.popdesk.start.StorageSteps.storage;
import static io.popdesk.start.StorageSteps.uploaded;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.attachment.ImageSteps;
import io.popdesk.start.StorageSteps.Gate;
import io.popdesk.start.StorageSteps.S3;
import io.popdesk.start.StorageSteps.Scene;
import io.popdesk.start.StorageSteps.Shared;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import io.popdesk.support.Wait;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Changing the storage settings between two starts on the same database: files uploaded before are
 * read and removed where their rows say, nothing is moved, and only new files go to the storage the
 * settings name now. Each test has a database, folders, and buckets of its own.
 */
class StorageSwitchTest extends OwnStartTest {

    private static final Duration PAST_A_DAY = Duration.ofHours(25);

    /** The cleanup wakes every second of real time; allow a few rounds. */
    private static final Duration CLEANUP = Duration.ofSeconds(40);

    private static final Shared<S3> S3_STAND_IN = new Shared<>("starting the S3 stand-in", S3::start);

    private final List<Path> folders = new ArrayList<>();

    @AfterAll
    static void stopStandIn() {
        S3 s3 = S3_STAND_IN.peek();
        if (s3 != null) {
            s3.close();
        }
    }

    @AfterEach
    void removeFolders() {
        folders.forEach(StorageSteps::removeFolder);
    }

    /** One place files can be in: the database, a folder, or a bucket. */
    private record Place(String kind, Database db, Path folder, S3 s3, String bucket) {

        boolean holds(String id) {
            return switch (kind) {
                case "db" -> contentRows(db, id) == 1;
                case "disk" -> Files.isRegularFile(fileOf(folder, id));
                default -> s3.has(bucket, keyOf(id));
            };
        }

        /** Adds this place's keys as the storage of new files. */
        Settings asStorage(Settings settings) {
            settings.set("POPDESK_STORAGE", kind);
            return switch (kind) {
                case "db" -> settings;
                case "disk" -> settings.set("POPDESK_STORAGE_DIR", folder.toString());
                default -> StorageSteps.withS3Keys(settings, s3, bucket);
            };
        }

        /** What a new row in this place says, or null where this slice does not fix it ({@code db}). */
        String rowLocation() {
            return switch (kind) {
                case "db" -> null;
                case "disk" -> folder.toString();
                default -> locationOf(bucket);
            };
        }

        @Override
        public String toString() {
            return kind + (folder != null ? " " + folder : bucket != null ? " " + bucket : "");
        }
    }

    private Place place(String name, Database db) {
        return switch (name) {
            case "db" -> new Place("db", db, null, null, null);
            case "disk", "folderA", "folderB" -> {
                Path folder = StorageSteps.newFolder("files");
                folders.add(folder);
                yield new Place("disk", db, folder, null, null);
            }
            default -> {
                S3 s3 = S3_STAND_IN.get();
                yield new Place("s3", db, null, s3, s3.newBucket());
            }
        };
    }

    /** The settings after a change: {@code to} for new files, and the S3 keys that rows in {@code from} still need. */
    private static Settings changed(Database db, Place from, Place to) {
        Settings settings = Settings.firstInstall(db);
        if (from.kind().equals("s3")) {
            StorageSteps.withS3Keys(settings, from.s3(), from.bucket());
        }
        return to.asStorage(settings);
    }

    @ParameterizedTest(name = "{0} to {1}")
    @CsvSource({"db,disk", "disk,s3", "s3,db", "folderA,folderB", "bucketA,bucketB"})
    @DisplayName("S5B-AC-10, S5B-AC-11 after the storage settings change, a file uploaded before downloads with the "
            + "same hash, its row and its file stay where they were, a new file goes to the new place only, and "
            + "deleting the old file removes it from the old place")
    void oldFilesStayWhereTheirRowsSay(String fromName, String toName) {
        Database db = postgres.newDatabase();
        Place from = place(fromName, db);
        Place to = place(toName, db);
        byte[] oldContent = bytes(MB + 20_000, Unique.number());
        byte[] keptContent = small();
        Scene s;
        String key;
        String oldId;
        String keptId;
        try (Boot app = Boot.start(from.asStorage(Settings.firstInstall(db)))) {
            app.assertStarted();
            s = scene(app);
            key = fileIssue(s.customer(), s.code());
            oldId = uploaded(s.customer(), key, "before-the-change.bin", oldContent);
            keptId = uploaded(s.customer(), key, "kept.bin", keptContent);
        }
        String oldStorage = storage(db, oldId);
        String oldLocation = location(db, oldId);
        assertThat(oldStorage).as("storage of the row before the change").isEqualTo(from.kind());
        assertThat(from.holds(oldId)).as("the old file in %s before the change", from).isTrue();

        try (Boot app = Boot.start(changed(db, from, to))) {
            app.assertStarted();
            Session customer = again(app, s.customer());

            // Read from where the row says.
            ApiResponse old = download(customer, key, oldId);
            assertThat(old.status()).as("%s %s", old.request(), old.snippet()).isEqualTo(200);
            assertThat(old.sha256()).isEqualTo(sha256(oldContent));
            // Not moved.
            assertThat(storage(db, oldId)).as("storage of the old row").isEqualTo(oldStorage);
            assertThat(location(db, oldId)).as("storage_location of the old row").isEqualTo(oldLocation);
            assertThat(from.holds(oldId)).as("the old file is still in %s", from).isTrue();
            assertThat(to.holds(oldId)).as("the old file was copied to %s", to).isFalse();

            // New files go to the new place only.
            byte[] newContent = small();
            String newId = uploaded(customer, key, "after-the-change.bin", newContent);
            assertThat(to.holds(newId)).as("the new file in %s", to).isTrue();
            assertThat(from.holds(newId)).as("the new file in %s", from).isFalse();
            assertThat(storage(db, newId)).as("storage of the new row").isEqualTo(to.kind());
            if (to.rowLocation() != null) {
                String newLocation = location(db, newId);
                assertThat(to.kind().equals("disk") ? Path.of(newLocation).toString() : newLocation)
                        .as("storage_location of the new row").isEqualTo(to.rowLocation());
            }
            assertThat(download(customer, key, newId).expectStatus(200).body()).isEqualTo(newContent);

            // Removed from where the row says.
            assertThat(delete(customer, key, oldId).status()).isEqualTo(204);
            assertThat(state(db, oldId)).isEqualTo("purged");
            assertThat(from.holds(oldId)).as("the deleted file is still in %s", from).isFalse();
            assertThat(from.holds(keptId)).as("the other old file in %s", from).isTrue();
            assertThat(download(customer, key, keptId).expectStatus(200).body()).isEqualTo(keptContent);
        }
    }

    @Test
    @DisplayName("S5B-AC-12 after the storage changes from one folder to another, the cleanup of temporary images "
            + "removes an expired image's file from the folder its row names, and the row")
    void temporaryImageCleanupRemovesFromTheOldPlace() {
        Database db = postgres.newDatabase();
        Place from = place("folderA", db);
        Place to = place("folderB", db);
        String imageId;
        try (Boot app = Boot.start(StorageSteps.withTestClock(from.asStorage(Settings.firstInstall(db))))) {
            app.assertStarted();
            Scene s = scene(app);
            imageId = ImageSteps.uploaded(s.customer());
        }
        assertThat(from.holds(imageId)).as("the temporary image in %s before the change", from).isTrue();

        try (Boot app = Boot.start(StorageSteps.withTestClock(changed(db, from, to)))) {
            app.assertStarted();
            StorageSteps.clock(app).advance(PAST_A_DAY);

            Wait.untilAsserted("the expired image's file and row are gone", CLEANUP, () -> {
                assertThat(from.holds(imageId)).as("the image's file in %s", from).isFalse();
                assertThat(StorageSteps.rows(db, imageId)).as("rows of the image").isEmpty();
            });
            assertThat(to.holds(imageId)).as("the image's file in %s", to).isFalse();
        }
    }

    @Test
    @DisplayName("S5B-AC-12 after the storage changes from s3 to db, the attachment cleanup removes the object of a "
            + "purging row from the bucket its row names, and the row ends purged")
    void attachmentCleanupRemovesFromTheOldPlace() {
        Database db = postgres.newDatabase();
        Place from = place("s3", db);
        Place to = place("db", db);
        String id;
        try (Boot app = Boot.start(StorageSteps.withTestClock(from.asStorage(Settings.firstInstall(db))))) {
            app.assertStarted();
            Scene s = scene(app);
            String key = fileIssue(s.customer(), s.code());
            id = uploaded(s.customer(), key, "removal-fails.bin", small());
            from.s3().gate().mode(Gate.Mode.DROP);
            try {
                delete(s.customer(), key, id).expectStatus(204);
            } finally {
                from.s3().gate().mode(Gate.Mode.PASS);
            }
        }
        assertThat(state(db, id)).as("state of the row whose removal failed").isEqualTo("purging");
        assertThat(from.holds(id)).as("the object in %s before the change", from).isTrue();

        try (Boot app = Boot.start(StorageSteps.withTestClock(changed(db, from, to)))) {
            app.assertStarted();
            StorageSteps.clock(app).advance(PAST_A_DAY);

            Wait.untilAsserted("the cleanup removed the object and ended the row", CLEANUP, () -> {
                assertThat(state(db, id)).isEqualTo("purged");
                assertThat(from.holds(id)).as("the object in %s", from).isFalse();
            });
        }
    }
}
