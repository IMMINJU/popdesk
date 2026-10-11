package io.popdesk.ops;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.ops.AuditTrail.Mark;
import io.popdesk.ops.AuditTrail.Row;
import io.popdesk.support.ApiClient;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The target of the sign-in audit rows. {@link AuditLogTest} checks the person of these rows; the
 * person and the target are the same account, so a check that the row mentions the account says
 * nothing about the target.
 *
 * <p>The audit table's columns are not fixed, so the target is found by what it does: the columns of
 * a row, other than the one holding the person, whose value names the account. The same action is
 * done by two accounts and only columns that name each row's own account count, so a row ID that
 * happens to equal an account ID is not taken for a target.
 */
class SignInAuditTargetTest extends BareAppTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> PERSON_FIELDS = Set.of("accountId", "email", "name");

    private AuditTrail audit;

    @BeforeEach
    void setUp() {
        audit = new AuditTrail(db);
    }

    /** A column value read as JSON when it is text holding a JSON object or array. */
    private static JsonNode read(JsonNode value) {
        if (value.isString()) {
            String text = value.stringValue().strip();
            if (text.startsWith("{") || text.startsWith("[")) {
                try {
                    return JSON.readTree(text);
                } catch (JacksonException notJson) {
                    return value;
                }
            }
        }
        return value;
    }

    private static boolean isPerson(JsonNode value) {
        return value.isObject() && fieldNames(value).equals(PERSON_FIELDS);
    }

    private static Set<String> fieldNames(JsonNode object) {
        Set<String> names = new LinkedHashSet<>();
        object.propertyNames().forEach(names::add);
        return names;
    }

    private static boolean names(JsonNode value, long accountId) {
        if (value.isIntegralNumber()) {
            return value.asLong() == accountId;
        }
        if (value.isString()) {
            return value.stringValue().equals(Long.toString(accountId));
        }
        for (JsonNode child : value.values()) {
            if (names(read(child), accountId)) {
                return true;
            }
        }
        return false;
    }

    /** The columns of the row, other than the person, whose value names the account. */
    private static Set<String> columnsNaming(Row row, long accountId) {
        Set<String> out = new LinkedHashSet<>();
        for (String column : fieldNames(row.json())) {
            JsonNode value = read(row.json().path(column));
            if (!isPerson(value) && names(value, accountId)) {
                out.add(column);
            }
        }
        return out;
    }

    private void failSignIn(ApiClient browser, String email, String password) {
        browser.post("/api/public/sign-in").json(Map.of("email", email, "password", password)).expect(401);
    }

    /** Does the action as {@code who} and returns its new row of that kind. */
    private Row rowOf(String kind, Session who) {
        Mark mark = audit.mark(who.email());
        switch (kind) {
            case "SIGN_IN_SUCCEEDED" -> fixtures.signInAgain(who);
            case "SIGN_IN_FAILED" -> failSignIn(console(), who.email(), "wrong-" + Unique.number() + "-Password");
            default -> {
                ApiClient browser = console();
                String wrong = "wrong-" + Unique.number() + "-Password";
                for (int i = 0; i < 5; i++) {
                    failSignIn(browser, who.email(), wrong);
                }
            }
        }
        List<Row> rows = audit.newRows(mark).stream().filter(r -> r.hasText(kind)).toList();
        assertThat(rows).as("new %s rows of %s", kind, who.email()).hasSize(1);
        return rows.getFirst();
    }

    /** The target columns of rows of this kind: those that name the own account in two accounts' rows. */
    private Set<String> targetColumns(String kind) {
        Session admin = fixtures.signInAdmin();
        Session one = fixtures.agent(admin);
        Session other = fixtures.agent(admin);
        Row ofOne = rowOf(kind, one);
        Row ofOther = rowOf(kind, other);
        Set<String> columns = columnsNaming(ofOne, one.accountId());
        columns.retainAll(columnsNaming(ofOther, other.accountId()));
        assertThat(columns).as("columns besides the person that name the account, in %s and %s", ofOne, ofOther)
                .isNotEmpty();
        assertThat(columnsNaming(ofOne, other.accountId())).as("columns of %s naming another account", ofOne)
                .doesNotContainAnyElementsOf(columns);
        return columns;
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"SIGN_IN_SUCCEEDED", "SIGN_IN_FAILED", "ACCOUNT_LOCKED"})
    @DisplayName("S1A-AC-64 the audit row of a sign-in, a failed sign-in, and a lock names that account as its "
            + "target, besides the person")
    void targetIsTheAccount(String kind) {
        assertThat(targetColumns(kind)).isNotEmpty();
    }

    @Test
    @DisplayName("S1A-AC-64 a failed sign-in with an email no account has leaves a row whose target is null, "
            + "without the email or the password that was tried")
    void unknownEmailHasNoTarget() {
        Set<String> targetColumns = targetColumns("SIGN_IN_FAILED");
        String email = Unique.email("nobody");
        String tried = "tried-" + Unique.number() + "-Password";
        Mark mark = audit.mark("SIGN_IN_FAILED");

        failSignIn(console(), email, tried);

        List<Row> rows = audit.newRows(mark);
        assertThat(rows).as("new SIGN_IN_FAILED rows after a sign-in with an email no account has").hasSize(1);
        Row row = rows.getFirst();
        assertThat(targetColumns).as("target columns that are null in %s", row)
                .allMatch(column -> row.json().path(column).isNull());
        assertThat(row.contains(email)).as("the email in %s", row).isFalse();
        assertThat(row.contains(tried)).as("the tried password in %s", row).isFalse();
    }
}
