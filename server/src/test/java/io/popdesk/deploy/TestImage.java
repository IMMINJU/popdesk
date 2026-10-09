package io.popdesk.deploy;

import java.time.Duration;

/**
 * The app image under test: {@code POPDESK_TEST_IMAGE}, or {@code popdesk:test}. The tests never
 * build it; build it first with {@code docker build -t popdesk:test .} from the repository root.
 */
final class TestImage {

    static final String DEFAULT = "popdesk:test";

    private TestImage() {}

    static String name() {
        String name = System.getenv("POPDESK_TEST_IMAGE");
        return name == null || name.isBlank() ? DEFAULT : name.strip();
    }

    /**
     * Fails unless the Dockerfile is there and the image exists locally, and returns the image name.
     * Run this before starting anything, so a missing image is a failed assertion and not a Compose
     * error.
     */
    static String require() {
        Repo.dockerfile();
        Cli.Result inspect = Cli.run(Duration.ofSeconds(30), Cli.docker("image", "inspect", "--format", "{{.Id}}", name()));
        if (!inspect.ok()) {
            throw new AssertionError("The image " + name() + " does not exist. Build it with `docker build -t "
                    + DEFAULT + " .` from the repository root, or set POPDESK_TEST_IMAGE.\n" + inspect);
        }
        return name();
    }

    /** The image ID ({@code sha256:...}): the digest that pins the exact image. */
    static String id() {
        return Cli.run(Duration.ofSeconds(30), Cli.docker("image", "inspect", "--format", "{{.Id}}", require()))
                .expectOk("docker image inspect " + name())
                .out().strip();
    }
}
