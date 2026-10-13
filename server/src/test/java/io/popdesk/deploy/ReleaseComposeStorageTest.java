package io.popdesk.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The S3 keys of the release Compose file, {@code deploy/compose.yml}: the app gets each of the six
 * only when the operator set it, as it gets every other optional key. Read with
 * {@code docker compose config}; nothing is started. That the {@code popdesk-attachments} volume
 * stays at {@code /data/attachments} is {@link ReleaseComposeTest}'s.
 */
@Tag("deploy")
class ReleaseComposeStorageTest {

    private static final List<String> S3_KEYS = List.of("S3_ENDPOINT", "S3_REGION", "S3_BUCKET", "S3_ACCESS_KEY",
            "S3_SECRET_KEY", "S3_SECRET_KEY_FILE");

    @Test
    @DisplayName("S5B-AC-50 the release Compose file passes the six S3 keys to the app when they are set, each with "
            + "its value, and passes none of them when they are not set")
    void s3KeysArePassedOnlyWhenSet() {
        Map<String, String> values = new LinkedHashMap<>();
        for (String key : S3_KEYS) {
            values.put(key, "value-of-" + key.toLowerCase());
        }

        Map<String, Object> set;
        try (Stack stack = Stack.of("compose", Repo.releaseCompose())) {
            values.forEach(stack::env);
            set = ComposeModel.resolved(stack.config()).environment("app");
        }
        Map<String, Object> unset;
        try (Stack stack = Stack.of("compose", Repo.releaseCompose())) {
            unset = ComposeModel.resolved(stack.config()).environment("app");
        }

        for (String key : S3_KEYS) {
            assertThat(set.get(key)).as("%s in the app's environment when it is set", key)
                    .isEqualTo(values.get(key));
            assertThat(unset.get(key)).as("%s in the app's environment when it is not set", key).isNull();
        }
        assertThat(set.get("POPDESK_DB_USER")).as("a key the file always sets, to see the environment was read")
                .isNotNull();
    }
}
