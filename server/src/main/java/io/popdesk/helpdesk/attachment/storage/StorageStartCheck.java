package io.popdesk.helpdesk.attachment.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The storage check of a start (design 5b section 2, ATT-9), after the migrations and before the app
 * takes a request. In this order:
 *
 * <ol>
 *   <li>The rows that are not {@code purged} are counted by storage kind and location. An installation
 *       whose settings and rows are all {@code db} is done here: no folder is touched and no connection
 *       goes to S3 (S5B-AC-27).
 *   <li>Rows in {@code s3} need the S3 keys, whatever the storage of new files is (S5B-AC-22).
 *   <li>Every place rows point to must have its marker. A place without one is a volume that was not
 *       attached or a bucket that is gone, and the app does not start (S5B-AC-21).
 *   <li>The storage of new files gets its marker when no row points to it yet: it is new (S5B-AC-20).
 *       A marker that is there is never written again.
 *   <li>Something small is written to the storage of new files and removed (S5B-AC-24).
 * </ol>
 *
 * A failed check logs one line that says what to do and fails the start with an exception, as the
 * migration checks do. The line names setting keys, the storage kind, the location, and how many
 * attachments are there; never a key's value or a file name (S5B-AC-25).
 *
 * <p>An operator who knows the files are lost and wants the app up puts the marker back by hand
 * (configuration.md "Attachments"); downloads of those files then answer 500.
 */
@Component
class StorageStartCheck implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(StorageStartCheck.class);

    /** How many attachments that are not {@code purged} one place holds. */
    private record Pointed(String kind, String location, long attachments) {}

    private final AttachmentStorages storages;
    private final JdbcTemplate jdbc;

    StorageStartCheck(AttachmentStorages storages, JdbcTemplate jdbc) {
        this.storages = storages;
        this.jdbc = jdbc;
    }

    @Override
    public void afterPropertiesSet() {
        AttachmentStorage current = storages.current();
        List<Pointed> pointed = jdbc.query("select storage, storage_location, count(*) from attachment "
                        + "where state <> 'purged' group by storage, storage_location order by storage, storage_location",
                (rs, row) -> new Pointed(rs.getString(1), rs.getString(2), rs.getLong(3)));
        List<Pointed> outside = pointed.stream().filter(p -> !DatabaseAttachmentStorage.KIND.equals(p.kind())).toList();
        boolean newFilesOutside = !DatabaseAttachmentStorage.KIND.equals(current.kind());
        if (outside.isEmpty() && !newFilesOutside) {
            return;
        }

        long inS3 = outside.stream().filter(p -> S3AttachmentStorage.KIND.equals(p.kind()))
                .mapToLong(Pointed::attachments).sum();
        if (inS3 > 0) {
            List<String> missing = storages.settings().missingS3Keys(false);
            if (!missing.isEmpty()) {
                throw stop(inS3 + " attachments are stored in s3 and these settings are missing: "
                        + String.join(", ", missing) + ". popdesk reads old files where they were stored, so it "
                        + "did not start. Set the S3 keys again (docs/operate/configuration.md).");
            }
        }
        if ((inS3 > 0 || S3AttachmentStorage.KIND.equals(current.kind())) && storages.settings().plainEndpoint()) {
            log.warn("S3_ENDPOINT is an http address: the requests signed with the S3 keys and the attachment "
                    + "files travel unencrypted. Use https unless the storage is on the same private network.");
        }

        List<String> unusable = new ArrayList<>();
        for (Pointed place : outside) {
            String what = place.attachments() + " attachments are stored in the " + place.kind() + " storage "
                    + place.location();
            try {
                if (!storages.place(place.kind(), place.location()).hasMarker()) {
                    unusable.add(what + ", which has no marker " + StoragePaths.MARKER + ": the volume is not "
                            + "attached, the bucket is another one, or the files are gone. popdesk did not start. "
                            + "Attach the storage that holds the files (docs/operate/configuration.md).");
                }
            } catch (StorageException e) {
                unusable.add(what + ", and popdesk could not look for its marker " + StoragePaths.MARKER + ": "
                        + e.getMessage() + ". popdesk did not start.");
            }
        }
        if (!unusable.isEmpty()) {
            unusable.forEach(log::error);
            throw new IllegalStateException(String.join(" ", unusable));
        }

        if (newFilesOutside) {
            boolean hasRows = outside.stream().anyMatch(
                    p -> p.kind().equals(current.kind()) && p.location().equals(current.location()));
            try {
                if (!hasRows && !current.hasMarker()) {
                    current.writeMarker();
                    log.info("New {} storage {}: no attachment is stored there yet, so popdesk left its marker {} "
                            + "there.", current.kind(), current.location(), StoragePaths.MARKER);
                }
                current.checkWritable();
            } catch (StorageException e) {
                throw stop("The " + current.kind() + " storage " + current.location() + " cannot be used for new "
                        + "files: " + e.getMessage() + ". popdesk did not start."
                        + (DiskAttachmentStorage.KIND.equals(current.kind())
                                ? " popdesk runs as user " + runsAs() + "; the folder must exist as a folder that "
                                        + "this user can write."
                                : ""));
            }
        }
    }

    private static IllegalStateException stop(String message) {
        log.error(message);
        return new IllegalStateException(message);
    }

    /** The ID of the user this process runs as (10001 in the image), or the user's name where there is no such ID. */
    private static String runsAs() {
        try {
            return String.valueOf(Files.getAttribute(Path.of("/proc/self"), "unix:uid"));
        } catch (IOException | UnsupportedOperationException | IllegalArgumentException e) {
            return System.getProperty("user.name", "unknown");
        }
    }
}
