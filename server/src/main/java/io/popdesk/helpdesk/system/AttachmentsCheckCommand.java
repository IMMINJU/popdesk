package io.popdesk.helpdesk.system;

import io.popdesk.SecretFiles;
import io.popdesk.helpdesk.attachment.storage.AttachmentStorages;
import io.popdesk.helpdesk.attachment.storage.StorageException;
import io.popdesk.helpdesk.system.PopdeskCommand.Failed;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.OptionalLong;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * {@code popdesk attachments check} (S5B-AC-40 to S5B-AC-43): for every {@code stored} attachment, in ID
 * order, whether its file is where the row says it is and has the row's size. For after a restore, or
 * when a volume or a bucket may not be the one the database knows.
 *
 * <p>It prints {@code missing {storage} {id}} or {@code wrong-size {storage} {id}} for a file that is
 * not as its row says, then one line per place that has stored attachments,
 * {@code {storage} {location}: checked n, missing n, wrong size n}, by storage kind and location; or
 * {@code no stored attachments}. Never a file name, an uploader, or an issue.
 *
 * <p>It only looks: no row and no file is changed and nothing is recorded in the audit log, so it can
 * run while the app runs. The rows are read a page at a time, and S3 is asked once per attachment.
 */
final class AttachmentsCheckCommand {

    /** The exit code when every file was looked at and one or more is missing or of another size. */
    static final int NOT_AS_RECORDED = 2;

    private static final int PAGE = 500;

    /** What was found in one place: attachments checked, files missing, files of another size. */
    private static final class Counts {
        long checked;
        long missing;
        long wrongSize;
    }

    private final CommandDatabase database;
    private final Map<String, String> env;

    AttachmentsCheckCommand(CommandDatabase database, Map<String, String> env) {
        this.database = database;
        this.env = env;
    }

    /** Returns the exit code: 0, or {@value #NOT_AS_RECORDED}. Could it not look at everything, it throws. */
    int run(PrintStream out) throws SQLException {
        try (Connection c = database.connect()) {
            AttachmentStorages storages = storages(c);
            Map<String, Map<String, Counts>> places = new TreeMap<>();
            boolean asRecorded = true;
            UUID last = new UUID(0, 0);
            int read;
            do {
                read = 0;
                try (PreparedStatement s = c.prepareStatement("select id, storage, storage_location, size from "
                        + "attachment where state = 'stored' and id > ? order by id limit " + PAGE)) {
                    s.setObject(1, last);
                    try (ResultSet rs = s.executeQuery()) {
                        while (rs.next()) {
                            read++;
                            last = rs.getObject(1, UUID.class);
                            String storage = rs.getString(2);
                            String location = rs.getString(3);
                            Counts counts = places.computeIfAbsent(storage, k -> new TreeMap<>())
                                    .computeIfAbsent(location, k -> new Counts());
                            counts.checked++;
                            OptionalLong size = size(storages, storage, location, last);
                            if (size.isEmpty()) {
                                asRecorded = false;
                                counts.missing++;
                                out.println("missing " + storage + " " + last);
                            } else if (size.getAsLong() != rs.getLong(4)) {
                                asRecorded = false;
                                counts.wrongSize++;
                                out.println("wrong-size " + storage + " " + last);
                            }
                        }
                    }
                }
            } while (read == PAGE);
            if (places.isEmpty()) {
                out.println("no stored attachments");
            }
            places.forEach((storage, locations) -> locations.forEach((location, counts) -> out.println(storage + " "
                    + location + ": checked " + counts.checked + ", missing " + counts.missing + ", wrong size "
                    + counts.wrongSize)));
            return asRecorded ? 0 : NOT_AS_RECORDED;
        }
    }

    private AttachmentStorages storages(Connection c) {
        try {
            return new AttachmentStorages(c, settings());
        } catch (StorageException e) {
            throw new Failed(reason(e));
        }
    }

    private static OptionalLong size(AttachmentStorages storages, String storage, String location, UUID id) {
        try {
            return storages.checkSize(storage, location, id);
        } catch (StorageException e) {
            throw new Failed("Could not look at the " + storage + " storage " + location + ": " + reason(e)
                    + ". The check did not finish.");
        }
    }

    /** What a storage failure says: written by the storage for the operator, with no secret in it. */
    private static String reason(RuntimeException e) {
        return e.getMessage();
    }

    /** The container's environment as the app reads it: a secret may be given as {@code <NAME>_FILE}. */
    private UnaryOperator<String> settings() {
        return key -> {
            String value = env.get(key);
            String file = env.get(key + "_FILE");
            if (!SecretFiles.SECRETS.contains(key) || file == null || file.isEmpty()) {
                return value;
            }
            if (value != null) {
                throw new Failed("Both " + key + " and " + key + "_FILE are set. Set only one of them.");
            }
            try {
                return SecretFiles.cutOneLineBreak(Files.readString(Path.of(file), StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new Failed("Could not read the file that " + key + "_FILE names ("
                        + e.getClass().getSimpleName() + ").");
            }
        };
    }
}
