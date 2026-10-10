package io.popdesk.e2e.support;

import java.nio.file.Files;
import java.nio.file.Path;

/** Where the image and the popdesk checkout are. Both come from the environment, with local defaults. */
public final class Settings {

    private Settings() {}

    /** The image under test. The tests never build it. */
    public static String image() {
        return env("POPDESK_TEST_IMAGE", "popdesk:test");
    }

    /**
     * The popdesk checkout that holds the release Compose file: {@code POPDESK_REPO}, else the
     * directory Maven runs in when it holds {@code deploy/}, else its parent ({@code server/..}).
     */
    public static Path repo() {
        String set = System.getenv("POPDESK_REPO");
        if (set != null && !set.isBlank()) {
            return Path.of(set);
        }
        Path dir = Path.of("").toAbsolutePath();
        return Files.isDirectory(dir.resolve("deploy")) ? dir : dir.getParent();
    }

    /** The release Compose file, used as it is. Only the image is replaced, by an override file. */
    public static Path releaseCompose() {
        return repo().resolve("deploy").resolve("compose.yml");
    }

    /** Scratch space for env files, override files, and saved logs. */
    public static Path workDir(String name) {
        return Path.of("target", "e2e", name).toAbsolutePath();
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
