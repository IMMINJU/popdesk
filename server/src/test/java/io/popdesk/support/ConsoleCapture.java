package io.popdesk.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Copies everything the JVM writes to standard output and standard error into one buffer, while
 * still printing it. Installed once, before the first Spring test context starts, so the app's
 * startup log is in the buffer too. Tests read it through {@link AppLog}.
 */
final class ConsoleCapture {

    private static final ByteArrayOutputStream BUFFER = new ByteArrayOutputStream();
    private static boolean installed;

    private ConsoleCapture() {}

    static synchronized void install() {
        if (installed) {
            return;
        }
        System.setOut(new PrintStream(new Tee(System.out), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new Tee(System.err), true, StandardCharsets.UTF_8));
        installed = true;
    }

    /** Number of bytes captured so far; a position to read from later. */
    static int position() {
        synchronized (BUFFER) {
            return BUFFER.size();
        }
    }

    /** Everything captured from {@code position} on. */
    static String since(int position) {
        byte[] all;
        synchronized (BUFFER) {
            all = BUFFER.toByteArray();
        }
        int from = Math.min(position, all.length);
        return new String(all, from, all.length - from, StandardCharsets.UTF_8);
    }

    private static final class Tee extends OutputStream {

        private final OutputStream original;

        Tee(OutputStream original) {
            this.original = original;
        }

        @Override
        public void write(int b) {
            synchronized (BUFFER) {
                BUFFER.write(b);
            }
            try {
                original.write(b);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void write(byte[] b, int off, int len) {
            synchronized (BUFFER) {
                BUFFER.write(b, off, len);
            }
            try {
                original.write(b, off, len);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void flush() {
            try {
                original.flush();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
