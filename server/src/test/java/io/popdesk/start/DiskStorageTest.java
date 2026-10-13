package io.popdesk.start;

import static io.popdesk.start.StorageSteps.bytes;
import static io.popdesk.start.StorageSteps.contentRows;
import static io.popdesk.start.StorageSteps.fileIssue;
import static io.popdesk.start.StorageSteps.fileOf;
import static io.popdesk.start.StorageSteps.filesUnder;
import static io.popdesk.start.StorageSteps.location;
import static io.popdesk.start.StorageSteps.marker;
import static io.popdesk.start.StorageSteps.scene;
import static io.popdesk.start.StorageSteps.storage;
import static io.popdesk.start.StorageSteps.uploaded;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.attachment.ImageSteps;
import io.popdesk.start.StorageSteps.Scene;
import io.popdesk.start.StorageSteps.Shared;
import io.popdesk.support.Unique;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code POPDESK_STORAGE=disk}: one app on a new database and a new empty folder, shared by the tests
 * of this class and of {@link StorageContract}. Each test counts only what it uploaded.
 */
class DiskStorageTest extends StorageContract {

    /** The running app, its database, and its folder. */
    record Disk(Boot app, Database db, Path folder) implements Store {

        @Override
        public String kind() {
            return "disk";
        }

        @Override
        public boolean holds(String id) {
            return Files.isRegularFile(fileOf(folder, id));
        }

        @Override
        public void lose(String id) {
            StorageSteps.remove(fileOf(folder, id));
        }

        @Override
        public List<String> paths() {
            return filesUnder(folder);
        }

        @Override
        public List<String> strays() {
            List<String> inside = paths().stream().filter(p -> !p.equals(StorageSteps.MARKER))
                    .filter(p -> {
                        String[] parts = p.split("/");
                        return parts.length != 2 || !ImageSteps.UUID_FORM.matcher(parts[1]).matches()
                                || !parts[1].startsWith(parts[0]) || parts[0].length() != 2;
                    }).toList();
            // The folder is {temporary folder}/outer/{name}: nothing else may appear in either parent.
            Path base = folder.getParent().getParent();
            List<String> outside = filesUnder(base).stream()
                    .filter(p -> !base.resolve(p).normalize().startsWith(folder)).map(p -> "outside: " + p).toList();
            return java.util.stream.Stream.concat(inside.stream(), outside.stream()).toList();
        }
    }

    private static final Shared<Disk> RUNNING = new Shared<>("starting the app with POPDESK_STORAGE=disk", () -> {
        Database db = postgres.newDatabase();
        Path folder = StorageSteps.newFolder("files");
        Boot app = Boot.start(StorageSteps.disk(db, folder));
        app.assertStarted();
        return new Disk(app, db, folder);
    });

    @AfterAll
    static void stop() {
        Disk disk = RUNNING.peek();
        if (disk != null) {
            disk.app().close();
            StorageSteps.removeFolder(disk.folder());
        }
    }

    @Override
    Store store() {
        return RUNNING.get();
    }

    @Test
    @DisplayName("S5B-AC-01 with POPDESK_STORAGE=disk a new file's content is under POPDESK_STORAGE_DIR, at "
            + "{first two characters of the ID}/{ID}, not in the database, and its row says disk and the folder's "
            + "absolute path")
    void newFilesGoToTheFolder() throws IOException {
        Disk disk = RUNNING.get();
        Scene s = scene(disk.app());
        String key = fileIssue(s.customer(), s.code());
        byte[] content = bytes(70_000, Unique.number());

        String id = uploaded(s.customer(), key, "on-disk.bin", content);

        Path file = fileOf(disk.folder(), id);
        assertThat(file).as("the file of %s; files in the folder: %s", id, disk.paths()).isRegularFile();
        assertThat(Files.readAllBytes(file)).as("content of %s", file).isEqualTo(content);
        assertThat(storage(disk.db(), id)).as("storage of the row").isEqualTo("disk");
        String location = location(disk.db(), id);
        assertThat(location).as("storage_location of the row").isNotNull();
        assertThat(Path.of(location).isAbsolute()).as("%s is an absolute path", location).isTrue();
        assertThat(Path.of(location)).as("storage_location of the row").isEqualTo(disk.folder());
        assertThat(contentRows(disk.db(), id)).as("rows of attachment_content for %s", id).isZero();
    }

    @Test
    @DisplayName("S5B-AC-20, S5B-AC-24 the first start on an empty folder that no row points to starts, leaves the "
            + "marker .popdesk-storage in the folder, and logs an INFO line with disk and the folder; what the start "
            + "wrote to try the folder is gone: the folder holds the marker and attachment files only")
    void firstStartLeavesTheMarker() {
        Disk disk = RUNNING.get();

        assertThat(disk.strays()).as("files that are neither the marker nor an attachment's").isEmpty();
        assertThat(disk.paths()).as("files left by trying the folder").noneMatch(
                p -> p.startsWith(".popdesk-") && !p.equals(StorageSteps.MARKER));

        assertThat(marker(disk.folder())).as("the marker in the folder; files: %s", disk.paths()).isRegularFile();
        assertThat(StorageSteps.linesWith(disk.app(), disk.folder().toString()))
                .as("log lines of the start that name the folder %s", disk.folder())
                .anyMatch(line -> line.contains("INFO") && line.contains("disk"));
    }
}
