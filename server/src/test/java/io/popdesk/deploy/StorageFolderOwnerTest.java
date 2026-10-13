package io.popdesk.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.Wait;
import java.time.Duration;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * {@code POPDESK_STORAGE=disk} on a folder the app's user cannot write: a new volume that belongs to
 * root, in the real image, where the app runs as user 10001. The test JVM on a developer's machine
 * cannot make such a folder, so this case is here and not with the other start checks.
 */
@Tag("deploy")
class StorageFolderOwnerTest {

    private static final Pattern USER_ID = Pattern.compile("(?<![0-9])10001(?![0-9])");

    private Stack stack;

    @AfterEach
    void remove() {
        if (stack != null) {
            stack.close();
        }
    }

    @Test
    @DisplayName("S5B-AC-24 with POPDESK_STORAGE_DIR on a folder that belongs to root the app does not start, and "
            + "its log names the user ID it runs as, 10001")
    void aFolderOfAnotherOwnerStopsTheStart() {
        stack = Stack.release("owner").withResource("storage-root-owned.compose.yml");
        stack.appEnv("POPDESK_STORAGE", "disk").appEnv("POPDESK_STORAGE_DIR", "/data/other");
        stack.up();

        String log = Wait.until("the app's log naming the user ID 10001", Duration.ofMinutes(3), () -> {
            String now = stack.logs("app");
            return USER_ID.matcher(now).find() ? now : null;
        });

        assertThat(log.lines().filter(line -> USER_ID.matcher(line).find()))
                .as("log lines with 10001 also name the storage kind").anyMatch(line -> line.contains("disk"));
        JsonNode state = stack.inspect("app").path("State");
        assertThat(state.path("Health").path("Status").asString("")).as("health of the app, state %s", state)
                .isNotEqualTo("healthy");
        Cli.Result listed = stack.command("run", "--rm", "-T", "--entrypoint", "ls", "app", "-A", "/data/other");
        assertThat(listed.out().strip()).as("files in the folder after the failed starts\n%s", listed).isEmpty();
    }
}
