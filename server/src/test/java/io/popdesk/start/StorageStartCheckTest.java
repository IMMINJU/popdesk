package io.popdesk.start;

import static io.popdesk.start.StorageSteps.delete;
import static io.popdesk.start.StorageSteps.fileIssue;
import static io.popdesk.start.StorageSteps.filesUnder;
import static io.popdesk.start.StorageSteps.holdsNumber;
import static io.popdesk.start.StorageSteps.linesWith;
import static io.popdesk.start.StorageSteps.locationOf;
import static io.popdesk.start.StorageSteps.marker;
import static io.popdesk.start.StorageSteps.markerKey;
import static io.popdesk.start.StorageSteps.scene;
import static io.popdesk.start.StorageSteps.small;
import static io.popdesk.start.StorageSteps.state;
import static io.popdesk.start.StorageSteps.storage;
import static io.popdesk.start.StorageSteps.told;
import static io.popdesk.start.StorageSteps.uploaded;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.start.StorageSteps.S3;
import io.popdesk.start.StorageSteps.Scene;
import io.popdesk.start.StorageSteps.Shared;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The storage checks at start ({@code design.md} 2절): the shape of the settings, the marker of every
 * place that rows point to, the S3 keys that old rows need, and whether the storage of new files can
 * be written. Each test has a database, a folder, and a bucket of its own, and closes every app it
 * started. "Does not start" is read as in {@link Boot}; what the start told is its log and the
 * messages of the failure ({@link StorageSteps#told}).
 */
class StorageStartCheckTest extends OwnStartTest {

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

    private Path newFolder() {
        Path folder = StorageSteps.newFolder("files");
        folders.add(folder);
        return folder;
    }

    /** One installation: a database, and the storage its files went to. */
    private record Installed(Database db, String kind, Path folder, S3 s3, String bucket, List<String> ids) {

        /** The settings the files were uploaded with. */
        Settings settings() {
            return kind.equals("disk") ? StorageSteps.disk(db, folder) : StorageSteps.s3(db, s3, bucket);
        }

        /** The settings of the same installation after its storage was changed to {@code db}, keeping what old rows need. */
        Settings changedToDb() {
            Settings settings = Settings.firstInstall(db).set("POPDESK_STORAGE", "db");
            return kind.equals("disk") ? settings : StorageSteps.withS3Keys(settings, s3, bucket);
        }

        /** The row's location as the log may write it. */
        String location() {
            return kind.equals("disk") ? folder.toString() : locationOf(bucket);
        }

        boolean markerThere() {
            return kind.equals("disk") ? Files.isRegularFile(marker(folder)) : s3.has(bucket, markerKey());
        }

        void removeMarker() {
            if (kind.equals("disk")) {
                StorageSteps.remove(marker(folder));
            } else {
                s3.remove(bucket, markerKey());
            }
        }
    }

    /** Starts a new installation on {@code kind}, uploads {@code files} attachments, and closes the app. */
    private Installed installed(String kind, int files) {
        Database db = postgres.newDatabase();
        S3 s3 = kind.equals("s3") ? S3_STAND_IN.get() : null;
        Installed installed = new Installed(db, kind, kind.equals("disk") ? newFolder() : null, s3,
                s3 == null ? null : s3.newBucket(), new ArrayList<>());
        try (Boot app = Boot.start(installed.settings())) {
            app.assertStarted();
            Scene s = scene(app);
            String key = fileIssue(s.customer(), s.code());
            for (int i = 0; i < files; i++) {
                installed.ids().add(uploaded(s.customer(), key, "kept-" + i + ".bin", small()));
            }
        }
        assertThat(installed.markerThere()).as("the marker of the %s storage after its first start", kind).isTrue();
        return installed;
    }

    // S5B-AC-21

    @ParameterizedTest(name = "{0} rows, storage now {1}")
    @CsvSource({"disk,disk", "disk,db", "s3,s3", "s3,db"})
    @DisplayName("S5B-AC-21 with the marker gone from a place that rows point to, the app does not start, whether "
            + "that place is the storage of new files or an old one, and says the storage kind, the place, and "
            + "how many rows point to it")
    void rowsWithoutTheirMarkerStopTheStart(String kind, String now) {
        Installed installed = installed(kind, 4);
        installed.removeMarker();
        Settings settings = now.equals("db") ? installed.changedToDb() : installed.settings();

        try (Boot app = Boot.start(settings)) {
            app.assertDidNotStart("4 rows point to " + installed.location() + ", which has no marker");

            List<String> lines = linesWith(app, installed.location());
            assertThat(lines).as("what the start told about %s", installed.location()).isNotEmpty();
            assertThat(lines).as("a line with the place, the kind %s, and the number of rows (4)", kind)
                    .anyMatch(line -> line.contains(kind) && holdsNumber(line, 4, installed.location()));
        }
        assertThat(installed.markerThere()).as("the marker after the failed start").isFalse();
        for (String id : installed.ids()) {
            assertThat(state(installed.db(), id)).as("state of %s", id).isEqualTo("stored");
        }
    }

    @Test
    @DisplayName("S5B-AC-21 with rows that point to a folder that is now empty (a volume that was not attached), "
            + "the app does not start and puts nothing into the folder")
    void anEmptyFolderWithRowsStopsTheStart() throws IOException {
        Installed installed = installed("disk", 2);
        for (String file : filesUnder(installed.folder())) {
            StorageSteps.remove(installed.folder().resolve(file));
        }

        try (Boot app = Boot.start(installed.settings())) {
            app.assertDidNotStart("2 rows point to " + installed.folder() + ", which is empty");
        }
        assertThat(filesUnder(installed.folder())).as("files in the folder after the failed start").isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"disk", "s3"})
    @DisplayName("S5B-AC-21, S5B-AC-20 when every row that points to a place is purged, a missing marker does not "
            + "stop the start: the place counts as new and gets its marker again")
    void purgedRowsDoNotNeedTheMarker(String kind) {
        Database db = postgres.newDatabase();
        S3 s3 = kind.equals("s3") ? S3_STAND_IN.get() : null;
        Installed installed = new Installed(db, kind, kind.equals("disk") ? newFolder() : null, s3,
                s3 == null ? null : s3.newBucket(), new ArrayList<>());
        try (Boot app = Boot.start(installed.settings())) {
            app.assertStarted();
            Scene s = scene(app);
            String key = fileIssue(s.customer(), s.code());
            for (int i = 0; i < 2; i++) {
                String id = uploaded(s.customer(), key, "gone-" + i + ".bin", small());
                delete(s.customer(), key, id).expectStatus(204);
                assertThat(state(db, id)).isEqualTo("purged");
            }
        }
        installed.removeMarker();

        try (Boot app = Boot.start(installed.settings())) {
            app.assertStarted();
            assertReady(app);
            assertThat(installed.markerThere()).as("the marker after the start").isTrue();
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"disk", "s3"})
    @DisplayName("S5B-AC-20 a start that finds the marker leaves it as it is: it is not written again")
    void anExistingMarkerIsNotWrittenAgain(String kind) throws IOException {
        Installed installed = installed(kind, 1);
        String before = written(installed);

        try (Boot app = Boot.start(installed.settings())) {
            app.assertStarted();
            assertReady(app);
        }

        assertThat(written(installed)).as("the marker's content and time of writing").isEqualTo(before);
    }

    private static String written(Installed installed) throws IOException {
        if (installed.kind().equals("s3")) {
            return installed.s3().written(installed.bucket(), markerKey());
        }
        Path marker = marker(installed.folder());
        FileTime modified = Files.getLastModifiedTime(marker);
        return modified + " " + new String(Files.readAllBytes(marker), StandardCharsets.UTF_8);
    }

    // S5B-AC-22

    @ParameterizedTest(name = "storage now {0}, missing: {1}")
    @CsvSource({"db,all", "disk,all", "db,secret"})
    @DisplayName("S5B-AC-22, S5B-AC-25 with rows in s3 and the S3 keys taken out of the settings, the app does not "
            + "start, whether new files go to db or to disk, and names the missing keys without any key's value")
    void s3RowsNeedTheS3Keys(String now, String missing) {
        Installed installed = installed("s3", 2);
        S3 s3 = installed.s3();
        Settings settings = now.equals("db")
                ? Settings.firstInstall(installed.db()).set("POPDESK_STORAGE", "db")
                : StorageSteps.disk(installed.db(), newFolder());
        if (missing.equals("secret")) {
            StorageSteps.withS3Keys(settings, s3, installed.bucket()).without("S3_SECRET_KEY");
        }

        try (Boot app = Boot.start(settings)) {
            app.assertDidNotStart("2 rows point to s3 and the settings have no " + missing + " S3 key(s)");

            String told = told(app);
            assertThat(told).as("what the start told").contains("S3_SECRET_KEY");
            if (missing.equals("all")) {
                assertThat(told).as("what the start told").contains("S3_ACCESS_KEY");
            }
            StorageSteps.assertHoldsNoSecret(told, "what the start told", s3.accessKey(), s3.secretKey(),
                    installed.db().password());
        }
        assertThat(installed.markerThere()).as("the marker in the bucket after the failed start").isTrue();
    }

    // S5B-AC-23

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"POPDESK_STORAGE=file", "disk with a relative POPDESK_STORAGE_DIR", "s3 without S3_BUCKET",
        "s3 without S3_ACCESS_KEY", "s3 without S3_SECRET_KEY", "s3 without S3_REGION and S3_ENDPOINT"})
    @DisplayName("S5B-AC-23, S5B-AC-25 with storage settings of the wrong shape the app does not start and names "
            + "the key, without the value of any other key")
    void badShapeStopsTheStart(String shape) {
        Database db = postgres.newDatabase();
        S3 s3 = S3_STAND_IN.get();
        String relative = "relative-" + UUID.randomUUID().toString().substring(0, 8) + "/files";
        Settings settings;
        List<String> named;
        if (shape.startsWith("POPDESK_STORAGE=")) {
            settings = Settings.firstInstall(db).set("POPDESK_STORAGE", "file");
            named = List.of("POPDESK_STORAGE");
        } else if (shape.startsWith("disk")) {
            settings = Settings.firstInstall(db).set("POPDESK_STORAGE", "disk").set("POPDESK_STORAGE_DIR", relative);
            named = List.of("POPDESK_STORAGE_DIR");
        } else {
            settings = StorageSteps.s3(db, s3, s3.newBucket());
            named = new ArrayList<>();
            for (String key : List.of("S3_BUCKET", "S3_ACCESS_KEY", "S3_SECRET_KEY", "S3_REGION", "S3_ENDPOINT")) {
                if (shape.contains(key)) {
                    settings.without(key);
                    named.add(key);
                }
            }
        }

        try (Boot app = Boot.start(settings)) {
            app.assertDidNotStart(shape);

            String told = told(app);
            for (String key : named) {
                assertThat(told).as("what the start told names %s", key).contains(key);
            }
            StorageSteps.assertHoldsNoSecret(told, "what the start told", s3.accessKey(), s3.secretKey(),
                    db.password());
        }
        assertThat(Path.of(relative)).as("the relative folder, from the working directory").doesNotExist();
        assertThat(Path.of(relative).getParent()).as("the relative folder's parent").doesNotExist();
    }

    // S5B-AC-24

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"a wrong S3_SECRET_KEY", "a bucket that does not exist", "an S3_ENDPOINT nothing listens on"})
    @DisplayName("S5B-AC-24, S5B-AC-25 when the s3 storage of new files cannot be written, the app does not start, "
            + "does not make the bucket, and tells no key's value, plain or percent-encoded")
    void unusableS3StopsTheStart(String why) {
        Database db = postgres.newDatabase();
        S3 s3 = S3_STAND_IN.get();
        String bucket = s3.newBucket();
        String wrongSecret = "pd/wrong/" + UUID.randomUUID() + "+w=";
        String noBucket = "pd-none-" + UUID.randomUUID().toString().substring(0, 8);
        Settings settings = StorageSteps.s3(db, s3, bucket);
        if (why.contains("S3_SECRET_KEY")) {
            settings.set("S3_SECRET_KEY", wrongSecret);
        } else if (why.contains("bucket")) {
            settings.set("S3_BUCKET", noBucket);
        } else {
            settings.set("S3_ENDPOINT", "http://127.0.0.1:" + StorageSteps.closedPort());
        }

        try (Boot app = Boot.start(settings)) {
            app.assertDidNotStart("the storage has " + why);

            StorageSteps.assertHoldsNoSecret(told(app), "what the start told", s3.accessKey(), s3.secretKey(),
                    wrongSecret, db.password());
        }
        assertThat(s3.bucketExists(noBucket)).as("the bucket %s exists after the failed start", noBucket).isFalse();
        assertThat(s3.keys(bucket)).as("keys in the bucket the settings did not reach or could not write").isEmpty();
    }

    @Test
    @DisplayName("S5B-AC-24 when POPDESK_STORAGE_DIR is a file, not a folder, the app does not start and leaves the "
            + "file as it is")
    void aFileInTheFoldersPlaceStopsTheStart() throws IOException {
        Database db = postgres.newDatabase();
        Path file = newFolder().resolve("not-a-folder");
        Files.writeString(file, "a file where the folder should be", StandardCharsets.UTF_8);

        try (Boot app = Boot.start(StorageSteps.disk(db, file))) {
            app.assertDidNotStart("POPDESK_STORAGE_DIR is a file");
        }

        assertThat(file).isRegularFile().hasContent("a file where the folder should be");
    }

    // S5B-AC-27

    @Test
    @DisplayName("S5B-AC-27 an installation whose settings and rows are all db starts without touching a folder or "
            + "S3: a POPDESK_STORAGE_DIR that does not exist is not made, and no connection goes to S3_ENDPOINT")
    void dbOnlyTouchesNoOtherStorage() {
        Database db = postgres.newDatabase();
        S3 s3 = S3_STAND_IN.get();
        Path missing = newFolder().resolve("never-made");
        int connections = s3.gate().accepted();
        // No POPDESK_STORAGE at all, as an installation that came from 0.4.0 has it.
        Settings settings = StorageSteps.withS3Keys(Settings.firstInstall(db), s3, s3.newBucket())
                .set("POPDESK_STORAGE_DIR", missing.toString());

        try (Boot app = Boot.start(settings)) {
            app.assertStarted();
            assertReady(app);
            Scene s = scene(app);
            String key = fileIssue(s.customer(), s.code());
            String id = uploaded(s.customer(), key, "in-the-database.bin", small());

            assertThat(storage(db, id)).as("storage of the row").isEqualTo("db");
            assertThat(StorageSteps.contentRows(db, id)).as("rows of attachment_content").isEqualTo(1);
        }
        assertThat(missing).as("POPDESK_STORAGE_DIR, which did not exist").doesNotExist();
        assertThat(s3.gate().accepted()).as("connections to S3_ENDPOINT").isEqualTo(connections);
    }
}
