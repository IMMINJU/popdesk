package io.popdesk.helpdesk.mailin;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.springframework.web.multipart.MultipartFile;

/**
 * A named part of a received email as the attachment service takes a file (design.md 0절 8): its contents written to
 * a temporary file, transfer encoding undone, so its size is the real one ({@code writePending} writes that many
 * bytes). {@link #delete()} removes the temporary file once the email is handled.
 */
final class MailPartFile implements MultipartFile {

    private final String name;
    private final String contentType;
    private final Path file;
    private final long size;

    private MailPartFile(String name, String contentType, Path file, long size) {
        this.name = name;
        this.contentType = contentType;
        this.file = file;
        this.size = size;
    }

    /** Writes {@code contents} to a new temporary file; the stream is read to its end, not closed. */
    static MailPartFile of(String name, String contentType, InputStream contents) {
        try {
            Path file = Files.createTempFile("popdesk-mail-", ".part");
            long size = Files.copy(contents, file, StandardCopyOption.REPLACE_EXISTING);
            return new MailPartFile(name, contentType, file, size);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Removes the temporary file; one that is gone already is fine. */
    void delete() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String getName() {
        return "files";
    }

    @Override
    public String getOriginalFilename() {
        return name;
    }

    @Override
    public String getContentType() {
        return contentType;
    }

    @Override
    public boolean isEmpty() {
        return size == 0;
    }

    @Override
    public long getSize() {
        return size;
    }

    @Override
    public byte[] getBytes() throws IOException {
        return Files.readAllBytes(file);
    }

    @Override
    public InputStream getInputStream() throws IOException {
        return Files.newInputStream(file);
    }

    @Override
    public void transferTo(java.io.File dest) throws IOException {
        Files.copy(file, dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
}
