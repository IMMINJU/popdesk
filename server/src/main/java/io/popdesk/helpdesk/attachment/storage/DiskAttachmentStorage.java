package io.popdesk.helpdesk.attachment.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * {@link AttachmentStorage} in a folder ({@code POPDESK_STORAGE=disk}): the file of an attachment is at
 * {@code {folder}/{first two characters of the ID}/{ID}} ({@link StoragePaths}), so one folder never
 * holds every file. The location recorded on a row is the folder's absolute path.
 *
 * <p>A file is written under a temporary name next to its place, forced to the disk, and then moved
 * there in one step: a half-written file is never where a whole one is expected, and the contents are
 * on the disk before the row becomes {@code stored} (ATT-10). Removing a file removes both names.
 */
final class DiskAttachmentStorage implements AttachmentStorage {

    static final String KIND = "disk";

    private static final String MARKER_TEXT = "popdesk keeps attachment files in this folder. Do not remove this "
            + "file: without it popdesk does not start while attachments are stored here "
            + "(docs/operate/configuration.md).\n";

    private final Path folder;

    DiskAttachmentStorage(Path folder) {
        this.folder = folder;
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public String location() {
        return folder.toString();
    }

    @Override
    public void write(UUID attachmentId, InputStream contents, long size) {
        Path file = folder.resolve(StoragePaths.filePath(attachmentId));
        Path part = folder.resolve(StoragePaths.partPath(attachmentId));
        try {
            Files.createDirectories(file.getParent());
            try (FileChannel channel = FileChannel.open(part, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                OutputStream out = Channels.newOutputStream(channel);
                contents.transferTo(out);
                out.flush();
                channel.force(true);
            }
            Files.move(part, file, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw failed("write a file", e);
        }
    }

    @Override
    public byte[] read(UUID attachmentId, long offset, int length) {
        ByteBuffer piece = ByteBuffer.allocate(length);
        try (FileChannel channel = FileChannel.open(folder.resolve(StoragePaths.filePath(attachmentId)),
                StandardOpenOption.READ)) {
            int read;
            do {
                read = channel.read(piece, offset + piece.position());
            } while (read >= 0 && piece.hasRemaining());
        } catch (NoSuchFileException e) {
            throw new StorageFileMissingException("The disk storage has no file for the attachment");
        } catch (IOException e) {
            throw failed("read a file", e);
        }
        return piece.hasRemaining() ? Arrays.copyOf(piece.array(), piece.position()) : piece.array();
    }

    @Override
    public void delete(UUID attachmentId) {
        try {
            Files.deleteIfExists(folder.resolve(StoragePaths.filePath(attachmentId)));
            Files.deleteIfExists(folder.resolve(StoragePaths.partPath(attachmentId)));
        } catch (IOException e) {
            throw failed("remove a file", e);
        }
    }

    @Override
    public OptionalLong size(UUID attachmentId) {
        try {
            return OptionalLong.of(Files.size(folder.resolve(StoragePaths.filePath(attachmentId))));
        } catch (NoSuchFileException e) {
            return OptionalLong.empty();
        } catch (IOException e) {
            throw failed("look at a file", e);
        }
    }

    @Override
    public boolean hasMarker() {
        return Files.isRegularFile(folder.resolve(StoragePaths.MARKER));
    }

    @Override
    public void writeMarker() {
        try {
            Files.createDirectories(folder);
            Files.writeString(folder.resolve(StoragePaths.MARKER), MARKER_TEXT, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw failed("write its marker", e);
        }
    }

    @Override
    public void checkWritable() {
        Path check = folder.resolve(StoragePaths.WRITE_CHECK);
        try {
            Files.writeString(check, "popdesk\n", StandardCharsets.UTF_8);
            Files.delete(check);
        } catch (IOException e) {
            throw failed("write and remove a file", e);
        }
    }

    /** The failure by its kind only: an I/O error's own message holds paths. */
    private static StorageException failed(String what, IOException e) {
        return new StorageException("The disk storage could not " + what + " (" + e.getClass().getSimpleName() + ")");
    }
}
