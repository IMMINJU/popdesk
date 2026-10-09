package io.popdesk.start;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Standard output and standard error of the test JVM, for apps this package starts itself. The
 * support package captures the same for Spring test contexts, but only once a test context has
 * started; these tests may start an app before any test context exists. Installed once; if the
 * support capture comes later it wraps this one, and both keep a copy.
 *
 * <p>Apps that run at the same time write into the same buffer. Read the part after a {@link #mark()}
 * and look for values the test chose.
 */
final class Console {

    private static final ByteArrayOutputStream BUFFER = new ByteArrayOutputStream();
    private static boolean installed;

    private Console() {}

    static synchronized void install() {
        if (installed) {
            return;
        }
        System.setOut(new PrintStream(new Tee(System.out), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new Tee(System.err), true, StandardCharsets.UTF_8));
        installed = true;
    }

    /** The current end of the captured output. */
    static int mark() {
        install();
        synchronized (BUFFER) {
            return BUFFER.size();
        }
    }

    /** Everything written after {@code mark}. */
    static String since(int mark) {
        byte[] all;
        synchronized (BUFFER) {
            all = BUFFER.toByteArray();
        }
        int from = Math.min(mark, all.length);
        return new String(all, from, all.length - from, StandardCharsets.UTF_8);
    }

    static List<String> linesSince(int mark) {
        return since(mark).lines().toList();
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
