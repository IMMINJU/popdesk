package io.popdesk.deploy;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Frame;
import io.popdesk.support.Wait;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.testcontainers.DockerClientFactory;

/**
 * Runs a command in a container with a terminal attached, like {@code docker compose exec} without
 * {@code -T}: a Docker exec with a TTY, through the Docker API (nothing extra in the image). Each
 * answer is typed only after the command printed something and then went quiet, the way a person
 * waits for a prompt; typed earlier, the terminal could echo it before the command turns echo off.
 */
final class Terminal {

    /** What the command printed (prompts and all), and its exit code; -1 when it did not end. */
    record Session(String output, long exit) {}

    private static final Duration QUIET = Duration.ofMillis(700);

    private Terminal() {}

    static Session run(String containerId, List<String> command, List<String> answers) {
        DockerClient docker = DockerClientFactory.instance().client();
        String exec = docker.execCreateCmd(containerId)
                .withCmd(command.toArray(String[]::new))
                .withTty(true)
                .withAttachStdin(true)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .exec()
                .getId();
        StringBuffer output = new StringBuffer();
        long[] lastChange = {System.nanoTime()};
        boolean[] ended = {false};
        try (PipedOutputStream typing = new PipedOutputStream();
                PipedInputStream stdin = new PipedInputStream(typing)) {
            ResultCallback.Adapter<Frame> callback = docker.execStartCmd(exec)
                    .withTty(true)
                    .withStdIn(stdin)
                    .exec(new ResultCallback.Adapter<>() {
                        @Override
                        public void onNext(Frame frame) {
                            output.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
                            lastChange[0] = System.nanoTime();
                        }

                        @Override
                        public void onComplete() {
                            ended[0] = true;
                            super.onComplete();
                        }
                    });
            int seen = 0;
            for (String answer : answers) {
                int before = seen;
                Boolean prompted = Wait.until("a prompt from " + command, Duration.ofSeconds(60), () -> {
                    if (ended[0] || !running(docker, exec)) {
                        return Boolean.FALSE;
                    }
                    boolean grew = output.length() > before;
                    boolean quiet = System.nanoTime() - lastChange[0] > QUIET.toNanos();
                    return grew && quiet ? Boolean.TRUE : null;
                });
                if (!prompted) {
                    break;
                }
                typing.write((answer + "\r").getBytes(StandardCharsets.UTF_8));
                typing.flush();
                seen = output.length();
            }
            try {
                callback.awaitCompletion(60, TimeUnit.SECONDS);
            } catch (RuntimeException streamClosed) {
                // On Windows the attach stream can end with "pipe ended" instead of a clean end of
                // stream once the command exits; whether it ended is read from the exec below.
            }
            boolean done;
            try {
                Wait.until("the end of " + command, Duration.ofSeconds(10),
                        () -> running(docker, exec) ? null : Boolean.TRUE);
                done = true;
            } catch (AssertionError stillRunning) {
                done = false;
            }
            Long exit = docker.inspectExecCmd(exec).exec().getExitCodeLong();
            return new Session(output.toString(), done && exit != null ? exit : -1);
        } catch (IOException e) {
            throw new AssertionError("Typing into " + command + " failed: " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while running " + command, e);
        }
    }

    private static boolean running(DockerClient docker, String exec) {
        Boolean running = docker.inspectExecCmd(exec).exec().isRunning();
        return running == null || running;
    }
}
