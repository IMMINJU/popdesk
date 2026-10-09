package io.popdesk.deploy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Runs a command line ({@code docker}, {@code docker compose}, {@code python}) and returns what it
 * printed and its exit code. A command that fails is a result, not an exception: tests assert on
 * the exit code, so a missing file or a container that does not start ends in an
 * {@link AssertionError} (the red check, C-6, counts only those).
 */
final class Cli {

    /** What a command printed, and how it ended. {@code exit} is -1 when it ran out of time. */
    record Result(List<String> command, int exit, String out, String err) {

        boolean ok() {
            return exit == 0;
        }

        /** Fails with the command and both outputs unless the command ended with exit code 0. */
        Result expectOk(String what) {
            if (!ok()) {
                throw new AssertionError(what + ": exit " + exit + "\n" + this);
            }
            return this;
        }

        @Override
        public String toString() {
            return "$ " + String.join(" ", command) + "\n--- exit " + exit + "\n--- stdout\n" + tail(out)
                    + "\n--- stderr\n" + tail(err);
        }

        private static String tail(String text) {
            return text.length() <= 6000 ? text : "..." + text.substring(text.length() - 6000);
        }
    }

    private Cli() {}

    static Result run(Duration timeout, List<String> command) {
        return run(timeout, command, Map.of(), null, Repo.root());
    }

    /**
     * Runs {@code command} in {@code dir} with {@code env} added to this JVM's environment, writing
     * {@code stdin} (when not null) to its standard input and then closing it.
     */
    static Result run(Duration timeout, List<String> command, Map<String, String> env, byte[] stdin, Path dir) {
        ProcessBuilder builder = new ProcessBuilder(command).directory(dir.toFile());
        builder.environment().putAll(env);
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new AssertionError("Could not start " + String.join(" ", command) + ": " + e.getMessage(), e);
        }
        CompletableFuture<String> out = drain(process.getInputStream());
        CompletableFuture<String> err = drain(process.getErrorStream());
        try (OutputStream in = process.getOutputStream()) {
            if (stdin != null) {
                in.write(stdin);
            }
        } catch (IOException e) {
            // The command ended before reading its input; its exit code tells what happened.
        }
        try {
            boolean ended = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!ended) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
            return new Result(List.copyOf(command), ended ? process.exitValue() : -1, out.join(), err.join());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new AssertionError("Interrupted while running " + String.join(" ", command), e);
        }
    }

    static List<String> docker(String... args) {
        List<String> command = new ArrayList<>();
        command.add("docker");
        command.addAll(List.of(args));
        return command;
    }

    private static CompletableFuture<String> drain(InputStream stream) {
        return CompletableFuture.supplyAsync(() -> {
            try (stream) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                stream.transferTo(buffer);
                return buffer.toString(StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }
}
