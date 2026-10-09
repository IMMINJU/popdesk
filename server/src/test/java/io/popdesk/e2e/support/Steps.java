package io.popdesk.e2e.support;

import org.junit.jupiter.api.function.Executable;

/**
 * The flow's steps run in order and build on each other. When one fails, every later step fails
 * with an assertion that names it, instead of running on a broken installation or being skipped.
 */
public final class Steps {

    private String failed;

    public void run(String step, Executable body) throws Throwable {
        if (failed != null) {
            throw new AssertionError("step `" + step + "` cannot run because the earlier step `" + failed + "` failed");
        }
        try {
            body.execute();
        } catch (Throwable t) {
            failed = step;
            throw t;
        }
    }
}
