package io.popdesk.e2e.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Runs the docker CLI. Every call waits for the process and returns its exit code and output. */
public final class Docker {

    private Docker() {}

    public record Result(List<String> command, int exitCode, String output) {

        public boolean ok() {
            return exitCode == 0;
        }

        /** Fails the test with an assertion, not an exception, when the command did not succeed. */
        public Result assertOk() {
            assertThat(exitCode).as("`%s` exited with %d:%n%s", String.join(" ", command), exitCode, tail(output, 80))
                    .isZero();
            return this;
        }
    }

    public static Result run(String... args) {
        return run(null, List.of(args));
    }

    public static Result run(Path dir, List<String> args) {
        List<String> command = new ArrayList<>();
        command.add("docker");
        command.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
        if (dir != null) {
            pb.directory(dir.toFile());
        }
        try {
            Process p = pb.start();
            p.getOutputStream().close();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Thread reader = Thread.ofVirtual().start(() -> copy(p.getInputStream(), out));
            if (!p.waitFor(10, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                return new Result(command, -1, "timed out after 10 minutes");
            }
            reader.join();
            return new Result(command, p.exitValue(), out.toString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // No docker CLI: a failed command, so the caller asserts on it.
            return new Result(command, -1, "could not run docker: " + e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(command, -1, "interrupted");
        }
    }

    private static void copy(InputStream in, ByteArrayOutputStream out) {
        try (in) {
            in.transferTo(out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String tail(String text, int lines) {
        String[] all = text.split("\\R");
        return String.join(System.lineSeparator(), Arrays.copyOfRange(all, Math.max(0, all.length - lines), all.length));
    }
}
