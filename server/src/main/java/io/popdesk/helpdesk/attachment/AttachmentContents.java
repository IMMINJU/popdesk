package io.popdesk.helpdesk.attachment;

import java.io.IOException;
import java.io.InputStream;
import org.springframework.core.io.AbstractResource;

/**
 * The contents of one attachment as a {@link org.springframework.core.io.Resource} that Spring MVC
 * writes to the response, ranges included (S0-AC-59). Its stream reads one piece of at most
 * {@link #PIECE} bytes at a time and skips without reading, so a download holds one piece in memory and
 * a range request reads only from where it starts (architecture.md "Attachment storage").
 */
public final class AttachmentContents extends AbstractResource {

    /** 1 MB, the size of one read from the storage. */
    static final int PIECE = 1 << 20;

    /** Reads up to {@code length} bytes from {@code offset}. */
    @FunctionalInterface
    interface Pieces {
        byte[] read(long offset, int length);
    }

    private final long size;
    private final Pieces pieces;

    AttachmentContents(long size, Pieces pieces) {
        this.size = size;
        this.pieces = pieces;
    }

    @Override
    public long contentLength() {
        return size;
    }

    @Override
    public InputStream getInputStream() {
        return new PieceStream();
    }

    @Override
    public String getDescription() {
        return "attachment contents";
    }

    @Override
    public boolean equals(Object other) {
        return this == other;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(this);
    }

    private final class PieceStream extends InputStream {

        /** Offset in the file of the next byte to return. */
        private long position;
        private byte[] piece = new byte[0];
        /** Offset in the file of {@code piece[0]}. */
        private long pieceStart;

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            if (position >= size) {
                return -1;
            }
            if (position < pieceStart || position >= pieceStart + piece.length) {
                piece = pieces.read(position, (int) Math.min(PIECE, size - position));
                pieceStart = position;
                if (piece.length == 0) {
                    throw new IOException("The attachment's contents ended before its size");
                }
            }
            int from = (int) (position - pieceStart);
            int count = Math.min(length, piece.length - from);
            System.arraycopy(piece, from, buffer, offset, count);
            position += count;
            return count;
        }

        @Override
        public long skip(long n) {
            long skipped = Math.max(0, Math.min(n, size - position));
            position += skipped;
            return skipped;
        }
    }
}
