package io.popdesk.helpdesk.audit;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.helpdesk.audit.AuditChain.Values;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * An entry where only one of the two person values is anonymous already (10a verify.md 4절: PIT left
 * {@code |} → {@code &} of the actor alive). No path makes such an entry today, so the entry is built
 * here: the value still old must change, and the new text must be written, though the other value
 * needed no change.
 */
class AuditAnonymizerHalfAnonymousTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Instant AT = Instant.parse("2026-10-08T03:15:00.123456Z");
    private static final long ID = 42;
    private static final String ANON_NAME = "Deleted user";
    private static final String ANON_EMAIL = "deleted-42@invalid";
    private static final String ANONYMOUS =
            "{\"accountId\":42,\"name\":\"Deleted user\",\"email\":\"deleted-42@invalid\"}";
    private static final String OTHER_TARGET = "{\"type\":\"ACCOUNT\",\"accountId\":7}";
    private static final String KIM_TARGET = "{\"type\":\"ACCOUNT\",\"accountId\":42}";

    private static Values anonymize(Values row) {
        return AuditAnonymizer.anonymize(row, ID, ANON_NAME, ANON_EMAIL);
    }

    private static JsonNode tree(String json) {
        return JSON.readTree(json);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "email anonymous, name old | {\"accountId\":42,\"name\":\"Kim\",\"email\":\"deleted-42@invalid\"}",
        "name anonymous, email old | {\"accountId\":42,\"name\":\"Deleted user\",\"email\":\"kim@example.test\"}"
    })
    @DisplayName("S10A-AC-17 an actor with one value anonymous already: the other value becomes anonymous too")
    void actorHalfAnonymous(String what, String actor) {
        Values row = new Values("ACCOUNT_UPDATED", actor, OTHER_TARGET, null, AT);

        Values result = anonymize(row);

        assertThat(result).isNotSameAs(row);
        assertThat(tree(result.actor())).isEqualTo(tree(ANONYMOUS));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "email anonymous, name old | {\"name\":\"Kim\",\"email\":\"deleted-42@invalid\"}",
        "name anonymous, email old | {\"name\":\"Deleted user\",\"email\":\"kim@example.test\"}"
    })
    @DisplayName("S10A-AC-17 changes of the account with one value anonymous already: the other value becomes "
            + "anonymous too")
    void changesHalfAnonymous(String what, String changes) {
        Values row = new Values("ACCOUNT_UPDATED", ANONYMOUS, KIM_TARGET, changes, AT);

        Values result = anonymize(row);

        assertThat(tree(result.changes()))
                .isEqualTo(tree("{\"name\":\"Deleted user\",\"email\":\"deleted-42@invalid\"}"));
    }
}
