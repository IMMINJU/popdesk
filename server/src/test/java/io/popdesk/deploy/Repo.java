package io.popdesk.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Files of the repository that the deploy tests read: the Dockerfile, the Compose files, the
 * Grafana dashboards, the upgrade harness, and this package's test resources. None of them exist
 * before the slice is built; {@link #require} turns that into a failed assertion that names the
 * missing file.
 */
final class Repo {

    private Repo() {}

    /** The repository root: the parent of {@code server/}, where Maven runs the tests. */
    static Path root() {
        Path dir = Path.of("").toAbsolutePath();
        if (Files.isDirectory(dir.resolve("server")) && Files.isDirectory(dir.resolve("design"))) {
            return dir;
        }
        Path parent = dir.getParent();
        if (parent != null && Files.isDirectory(parent.resolve("design"))) {
            return parent;
        }
        throw new AssertionError("Could not find the repository root from " + dir);
    }

    static Path path(String relative) {
        return root().resolve(relative);
    }

    /** Fails unless {@code relative} (from the repository root) is a file, and returns it. */
    static Path require(String relative, String why) {
        Path file = path(relative);
        assertThat(file).as("%s: %s should exist (%s)", relative, file, why).isRegularFile();
        return file;
    }

    static Path dockerfile() {
        return require("Dockerfile", "the image that builds both web apps into the server, architecture.md section 2");
    }

    static Path releaseCompose() {
        return require("deploy/compose.yml", "the release Compose file, ADR D-45");
    }

    static Path devCompose() {
        return require("deploy/compose.dev.yml", "the development Compose file with Mailpit");
    }

    static Path obsCompose() {
        return require("deploy/compose.obs.yml", "the observability Compose file, architecture.md section 6");
    }

    static Path makeSample() {
        return require("deploy/upgrade/make-sample.py", "the sample data generator; see the deploy test README");
    }

    static Path upgradeHarness() {
        return require("deploy/upgrade/upgrade-test.py", "the upgrade test harness; see the deploy test README");
    }

    /** A file under {@code server/src/test/resources/deploy/}, as copied to the test classpath. */
    static Path resource(String name) {
        URL url = Repo.class.getClassLoader().getResource("deploy/" + name);
        if (url == null) {
            throw new AssertionError("Missing test resource deploy/" + name);
        }
        try {
            return Path.of(url.toURI());
        } catch (URISyntaxException e) {
            throw new AssertionError("Bad resource URL " + url, e);
        }
    }

    /** {@code server/src/test/resources/deploy/} as copied to the test classpath. */
    static Path resourceDir() {
        return resource("mail-tls").getParent();
    }

    /** A path as Compose accepts it in a bind mount on every OS: absolute, with forward slashes. */
    static String forCompose(Path path) {
        return path.toAbsolutePath().toString().replace('\\', '/');
    }
}
