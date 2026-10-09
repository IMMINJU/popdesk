package io.popdesk.start;

import io.popdesk.PopdeskApplication;
import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.AppTest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * One start of the app, in this JVM, with {@link Settings} as the first property source of its
 * {@code Environment} (where an installation's environment variables would be). Either the app
 * started and runs until {@link #close()}, or starting it failed and {@link #failure()} says why.
 *
 * <p>"The app does not start" is read as {@code SpringApplication.run} ending with an exception:
 * the app stops itself during startup, the way Spring Boot stops on any startup failure, without
 * {@code System.exit}.
 */
final class Boot implements AutoCloseable {

    private final ConfigurableApplicationContext context;
    private final Throwable failure;
    private final int mark;

    private Boot(ConfigurableApplicationContext context, Throwable failure, int mark) {
        this.context = context;
        this.failure = failure;
        this.mark = mark;
    }

    /** Starts the app and waits until it is up or has failed. */
    static Boot start(Settings settings) {
        int mark = Console.mark();
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("popdeskStartSettings", settings.asMap()));
        try {
            ConfigurableApplicationContext context = new SpringApplicationBuilder(PopdeskApplication.class)
                    .environment(environment)
                    .registerShutdownHook(false)
                    .run();
            return new Boot(context, null, mark);
        } catch (Throwable startFailed) {
            return new Boot(null, startFailed, mark);
        }
    }

    /** Starts the app on a thread of its own; the test goes on while it starts. */
    static Background startInBackground(Settings settings) {
        CompletableFuture<Boot> result = new CompletableFuture<>();
        Thread.ofPlatform().daemon().name("start-test-app").start(() -> result.complete(start(settings)));
        return new Background(result);
    }

    boolean started() {
        return context != null;
    }

    /** Why starting failed, or null when the app runs. */
    Throwable failure() {
        return failure;
    }

    /** Fails with an AssertionError, showing why, unless the app started. */
    Boot assertStarted() {
        if (failure != null) {
            throw new AssertionError("The app did not start: " + failure, failure);
        }
        return this;
    }

    /**
     * Fails with an AssertionError if the app started; closes it first. Use for "does not start":
     * before the slice is built the app starts anyway, and this turns that into a failed assertion.
     */
    void assertDidNotStart(String because) {
        if (context != null) {
            context.close();
            throw new AssertionError("The app started, but it should not: " + because);
        }
    }

    ConfigurableApplicationContext context() {
        assertStarted();
        return context;
    }

    /** The HTTP port (8080 in production). */
    int httpPort() {
        return port("local.server.port");
    }

    /** The management port (8081 in production). */
    int managementPort() {
        return port("local.management.port");
    }

    private int port(String property) {
        String value = context().getEnvironment().getProperty(property);
        if (value == null) {
            throw new AssertionError("The app published no " + property);
        }
        return Integer.parseInt(value);
    }

    /** A new browser on the console host. */
    ApiClient console() {
        return new ApiClient(AppTest.CONSOLE_HOST, httpPort());
    }

    /** A new browser on the portal host. */
    ApiClient portal() {
        return new ApiClient(AppTest.PORTAL_HOST, httpPort());
    }

    /** GET on the management port, as a health check does. */
    ApiResponse management(String path) {
        return new ApiClient("localhost", managementPort()).get(path).send();
    }

    /** Signs in on the console; returns the response (204 when it worked). */
    ApiResponse signIn(String email, String password) {
        return console().post("/api/public/sign-in").json(Map.of("email", email, "password", password)).send();
    }

    /** The output written since this start began (with other apps' output, if any ran meanwhile). */
    String log() {
        return Console.since(mark);
    }

    List<String> logLines() {
        return Console.linesSince(mark);
    }

    @Override
    public void close() {
        if (context != null) {
            context.close();
        }
    }

    /** A start running on its own thread. */
    static final class Background implements AutoCloseable {

        private final CompletableFuture<Boot> result;

        private Background(CompletableFuture<Boot> result) {
            this.result = result;
        }

        boolean done() {
            return result.isDone();
        }

        /** Waits until the start has ended, either way. Fails with an AssertionError after {@code atMost}. */
        Boot await(Duration atMost) {
            try {
                return result.get(atMost.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                throw new AssertionError("The app was still starting after " + atMost, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while the app started", e);
            } catch (ExecutionException e) {
                throw new AssertionError("Starting the app threw", e.getCause());
            }
        }

        /**
         * Closes the app if it started. If it is still starting (hanging in a migration), ends its
         * database connections first so the start fails and its thread ends.
         */
        void close(Database db) {
            if (!result.isDone()) {
                db.terminateAll();
            }
            try {
                result.get(60, TimeUnit.SECONDS).close();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException | TimeoutException e) {
                // The thread is a daemon; a start that never ends does not keep the JVM alive.
            }
        }

        @Override
        public void close() {
            if (result.isDone()) {
                result.join().close();
            }
        }
    }
}
