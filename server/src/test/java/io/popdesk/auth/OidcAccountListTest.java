package io.popdesk.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.privacy.PrivacySteps;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;

/**
 * {@code oidcLinked} of the account list (S11-AC-26): false before the account's first OIDC sign-in, true after it,
 * and still true on a suspended account, in the list narrowed to suspended accounts and in the answer of the
 * suspension ({@code SuspendedAccount}).
 */
class OidcAccountListTest extends OidcAppTest {

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"AGENT", "CUSTOMER"})
    @DisplayName("S11-AC-26 the account list gives oidcLinked false before the first OIDC sign-in and true after it, "
            + "for an agent and a customer, and another account stays false")
    void listGivesOidcLinked(Role role) {
        Session admin = fixtures.signInAdmin();
        Session person = person(admin, role);
        Session other = person(admin, role);
        assertThat(listed(admin, person).path("oidcLinked").isBoolean()).as("oidcLinked is a boolean").isTrue();
        assertThat(listed(admin, person).path("oidcLinked").booleanValue()).as("oidcLinked before").isFalse();

        linkByOidc(person);

        assertThat(listed(admin, person).path("oidcLinked").booleanValue()).as("oidcLinked after").isTrue();
        assertThat(listed(admin, other).path("oidcLinked").booleanValue()).as("oidcLinked of another account")
                .isFalse();
    }

    @Test
    @DisplayName("S11-AC-26 a linked account, once suspended, has oidcLinked true in the suspension's answer and in "
            + "the list of suspended accounts")
    void suspendedAccountStaysLinked() {
        Session admin = fixtures.signInAdmin();
        Session agent = person(admin, Role.AGENT);
        linkByOidc(agent);

        JsonNode suspended = admin.client().post(ACCOUNTS + "/" + agent.accountId() + "/suspend").expect(200).json();

        assertThat(suspended.path("status").asString("")).as("status in %s", suspended).isEqualTo("SUSPENDED");
        assertThat(suspended.path("oidcLinked").isBoolean()).as("oidcLinked in %s", suspended).isTrue();
        assertThat(suspended.path("oidcLinked").booleanValue()).as("oidcLinked in %s", suspended).isTrue();
        JsonNode row = PrivacySteps.accounts(admin, "status", "SUSPENDED", "q", agent.email()).stream()
                .filter(a -> a.path("id").asLong(-1) == agent.accountId()).findFirst().orElse(null);
        assertThat(row).as("the account in the list of suspended accounts").isNotNull();
        assertThat(row.path("oidcLinked").booleanValue()).as("oidcLinked in %s", row).isTrue();
    }

    private static JsonNode listed(Session admin, Session person) {
        return PrivacySteps.account(admin, person.accountId(), person.email());
    }
}
