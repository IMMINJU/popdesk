package io.popdesk.helpdesk.audit;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.helpdesk.audit.AuditChain.Values;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The person fields of one audit entry after an account is anonymized (10a design.md 0절 4): the
 * person is found by account ID, only {@code actor} and the top-level {@code name}/{@code email} of
 * {@code changes} change, and an entry with nothing to change comes back as the same object.
 */
class AuditAnonymizerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final Instant AT = Instant.parse("2026-10-08T03:15:00.123456Z");

    private static final long ID = 42;
    private static final String ANON_NAME = "Deleted user";
    private static final String ANON_EMAIL = "deleted-42@invalid";

    private static final String KIM = "{\"accountId\":42,\"name\":\"Kim\",\"email\":\"kim@example.test\"}";
    private static final String LEE = "{\"accountId\":7,\"name\":\"Lee\",\"email\":\"lee@example.test\"}";
    private static final String KIM_TARGET = "{\"type\":\"ACCOUNT\",\"accountId\":42}";
    private static final String OTHER_TARGET = "{\"type\":\"ACCOUNT\",\"accountId\":7}";
    private static final String NAME_CHANGE =
            "{\"name\":{\"from\":\"Kim\",\"to\":\"Kim Minju\"},\"email\":\"kim@example.test\"}";

    private static Values row(String actor, String target, String changes) {
        return new Values("ACCOUNT_UPDATED", actor, target, changes, AT);
    }

    private static Values anonymize(Values row) {
        return AuditAnonymizer.anonymize(row, ID, ANON_NAME, ANON_EMAIL);
    }

    private static JsonNode tree(String json) {
        return JSON.readTree(json);
    }

    private static List<String> keys(String json) {
        return List.copyOf(tree(json).propertyNames());
    }

    @Test
    @DisplayName("S10A-AC-17 the actor is the account: its name and email become the anonymous ones, "
            + "another account's target and changes stay")
    void actorOnly() {
        Values result = anonymize(row(KIM, OTHER_TARGET, NAME_CHANGE));

        assertThat(tree(result.actor()))
                .isEqualTo(tree("{\"accountId\":42,\"name\":\"Deleted user\",\"email\":\"deleted-42@invalid\"}"));
        assertThat(tree(result.target())).isEqualTo(tree(OTHER_TARGET));
        assertThat(tree(result.changes())).isEqualTo(tree(NAME_CHANGE));
    }

    @Test
    @DisplayName("S10A-AC-17 the target is the account: the top-level name and email of changes become the "
            + "anonymous ones, another account's actor stays")
    void targetOnly() {
        Values result = anonymize(row(LEE, KIM_TARGET, NAME_CHANGE));

        assertThat(tree(result.actor())).isEqualTo(tree(LEE));
        assertThat(tree(result.target())).isEqualTo(tree(KIM_TARGET));
        assertThat(tree(result.changes())).isEqualTo(tree(
                "{\"name\":{\"from\":\"Deleted user\",\"to\":\"Deleted user\"},\"email\":\"deleted-42@invalid\"}"));
    }

    @Test
    @DisplayName("S10A-AC-17 the account is both actor and target: both are changed")
    void actorAndTarget() {
        Values result = anonymize(row(KIM, KIM_TARGET, NAME_CHANGE));

        assertThat(tree(result.actor()))
                .isEqualTo(tree("{\"accountId\":42,\"name\":\"Deleted user\",\"email\":\"deleted-42@invalid\"}"));
        assertThat(tree(result.changes())).isEqualTo(tree(
                "{\"name\":{\"from\":\"Deleted user\",\"to\":\"Deleted user\"},\"email\":\"deleted-42@invalid\"}"));
    }

    static Stream<Arguments> nameAndEmailForms() {
        return Stream.of(
                Arguments.of("name as text", "{\"name\":\"Kim\"}", "{\"name\":\"Deleted user\"}"),
                Arguments.of("name as {from, to}", "{\"name\":{\"from\":\"Kim\",\"to\":\"Kim Minju\"}}",
                        "{\"name\":{\"from\":\"Deleted user\",\"to\":\"Deleted user\"}}"),
                Arguments.of("email as text", "{\"email\":\"kim@example.test\"}",
                        "{\"email\":\"deleted-42@invalid\"}"),
                Arguments.of("email as {from, to}",
                        "{\"email\":{\"from\":\"kim@example.test\",\"to\":\"minju@example.test\"}}",
                        "{\"email\":{\"from\":\"deleted-42@invalid\",\"to\":\"deleted-42@invalid\"}}"),
                Arguments.of("{from, to} with only to", "{\"name\":{\"to\":\"Kim\"}}",
                        "{\"name\":{\"to\":\"Deleted user\"}}"),
                Arguments.of("{from, to} with a null from", "{\"name\":{\"from\":null,\"to\":\"Kim\"}}",
                        "{\"name\":{\"from\":\"Deleted user\",\"to\":\"Deleted user\"}}"),
                Arguments.of("{from, to} with another key", "{\"name\":{\"from\":\"Kim\",\"to\":\"K\",\"by\":\"x\"}}",
                        "{\"name\":{\"from\":\"Deleted user\",\"to\":\"Deleted user\",\"by\":\"x\"}}"),
                Arguments.of("a number", "{\"name\":5}", "{\"name\":\"Deleted user\"}"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nameAndEmailForms")
    @DisplayName("S10A-AC-17 a top-level name or email of changes is replaced in each of its forms")
    void changesForms(String form, String changes, String expected) {
        Values result = anonymize(row(LEE, KIM_TARGET, changes));

        assertThat(tree(result.changes())).isEqualTo(tree(expected));
    }

    @Test
    @DisplayName("S10A-AC-17 other keys of actor, target, changes and nested objects keep their values and order; "
            + "action and occurredAt stay")
    void otherKeysStay() {
        String target = "{\"type\":\"ACCOUNT\",\"accountId\":42,\"code\":\"ACME\"}";
        String changes = "{\"role\":{\"from\":\"AGENT\",\"to\":\"ADMIN\"},\"name\":\"Kim\","
                + "\"profile\":{\"name\":\"Kim\",\"email\":\"kim@example.test\"},\"companyCode\":\"ACME\"}";
        Values result = anonymize(row(KIM, target, changes));

        assertThat(result.action()).isEqualTo("ACCOUNT_UPDATED");
        assertThat(result.occurredAt()).isEqualTo(AT);
        assertThat(tree(result.target())).isEqualTo(tree(target));
        assertThat(tree(result.changes())).isEqualTo(tree("{\"role\":{\"from\":\"AGENT\",\"to\":\"ADMIN\"},"
                + "\"name\":\"Deleted user\",\"profile\":{\"name\":\"Kim\",\"email\":\"kim@example.test\"},"
                + "\"companyCode\":\"ACME\"}"));
        assertThat(keys(result.changes())).containsExactly("role", "name", "profile", "companyCode");
        assertThat(keys(result.actor())).containsExactly("accountId", "name", "email");
    }

    @Test
    @DisplayName("S10A-AC-17 an actor without name and email keys gets them")
    void actorWithoutKeysGetsThem() {
        Values result = anonymize(row("{\"accountId\":42}", null, null));

        assertThat(tree(result.actor()))
                .isEqualTo(tree("{\"accountId\":42,\"name\":\"Deleted user\",\"email\":\"deleted-42@invalid\"}"));
    }

    @Test
    @DisplayName("S10A-AC-17 a name or email of changes that is missing or JSON null stays as it is")
    void missingOrNullChangeStays() {
        Values result = anonymize(row(KIM, KIM_TARGET, "{\"name\":null,\"role\":\"AGENT\"}"));

        assertThat(tree(result.changes())).isEqualTo(tree("{\"name\":null,\"role\":\"AGENT\"}"));
    }

    @Test
    @DisplayName("S10A-AC-17 an actor that is not JSON is left as it is while the changes are replaced")
    void actorThatIsNotJsonStays() {
        Values result = anonymize(row("not json", KIM_TARGET, "{\"name\":\"Kim\"}"));

        assertThat(result.actor()).isEqualTo("not json");
        assertThat(tree(result.changes())).isEqualTo(tree("{\"name\":\"Deleted user\"}"));
    }

    @Test
    @DisplayName("S10A-AC-17 another account with the same name and email keeps its values: the same entry back")
    void sameNameOtherAccount() {
        String sameNameActor = "{\"accountId\":7,\"name\":\"Kim\",\"email\":\"kim@example.test\"}";
        Values row = row(sameNameActor, OTHER_TARGET, "{\"name\":\"Kim\",\"email\":\"kim@example.test\"}");

        assertThat(anonymize(row)).isSameAs(row);
    }

    static Stream<Values> rowsWithNothingToChange() {
        return Stream.of(
                row(LEE, OTHER_TARGET, NAME_CHANGE),
                row(LEE, "{\"type\":\"COMPANY\",\"code\":\"ACME\"}", NAME_CHANGE),
                row(LEE, null, null),
                row(LEE, KIM_TARGET, null),
                row(LEE, KIM_TARGET, "null"),
                row(LEE, KIM_TARGET, "[\"name\"]"),
                row(LEE, KIM_TARGET, "\"Kim\""),
                row(LEE, KIM_TARGET, "{\"role\":{\"from\":\"AGENT\",\"to\":\"ADMIN\"}}"),
                row(LEE, KIM_TARGET, "{\"name\":null}"));
    }

    @ParameterizedTest
    @MethodSource("rowsWithNothingToChange")
    @DisplayName("S10A-AC-18 neither actor nor target is the account, or changes is null or not an object or "
            + "has no name or email: the same entry back")
    void nothingToChange(Values row) {
        assertThat(anonymize(row)).isSameAs(row);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"name\":\"Deleted user\",\"email\":\"deleted-42@invalid\"}",
        "{\"name\":{\"from\":\"Deleted user\",\"to\":\"Deleted user\"},"
                + "\"email\":{\"from\":\"deleted-42@invalid\",\"to\":\"deleted-42@invalid\"}}"})
    @DisplayName("S10A-AC-18 values that are anonymous already: the same entry back")
    void alreadyAnonymous(String changes) {
        Values row = row("{\"accountId\":42,\"name\":\"Deleted user\",\"email\":\"deleted-42@invalid\"}",
                KIM_TARGET, changes);

        assertThat(anonymize(row)).isSameAs(row);
    }
}
