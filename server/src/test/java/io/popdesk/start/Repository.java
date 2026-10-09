package io.popdesk.start;

import java.nio.file.Files;
import java.nio.file.Path;

/** Files of the repository the tests read: the CI scripts and the READMEs. */
final class Repository {

    private Repository() {}

    /** The repository root: the first folder up from the working directory with {@code scripts/ci}. */
    static Path root() {
        for (Path p = Path.of("").toAbsolutePath(); p != null; p = p.getParent()) {
            if (Files.isRegularFile(p.resolve("scripts/ci/check-tenant-id.py"))) {
                return p;
            }
        }
        throw new AssertionError("No scripts/ci/check-tenant-id.py above " + Path.of("").toAbsolutePath());
    }
}
