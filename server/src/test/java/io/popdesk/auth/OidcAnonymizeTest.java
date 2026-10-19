package io.popdesk.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.privacy.PrivacySteps;
import io.popdesk.support.Fixtures.Invitation;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Anonymizing an account removes its OIDC link in the same transaction (S11-AC-29): the account is no longer linked,
 * no {@code OIDC_UNLINKED} is written beside {@code ACCOUNT_ANONYMIZED}, and the person invited again with the same
 * email who signs in with the old sub reaches the new account, which the sign-in turns on, and not the old one.
 */
class OidcAnonymizeTest extends OidcAppTest {

    private static final String CONSOLE = "/api/console";

    @Test
    @DisplayName("S11-AC-29 anonymizing a linked account leaves oidcLinked false, adds ACCOUNT_ANONYMIZED and no "
            + "OIDC_UNLINKED, and the old sub with the same email reaches the account invited again, which becomes "
            + "ACTIVE and linked, while the old account stays unlinked")
    void anonymizingRemovesTheLink() {
        Session admin = fixtures.signInAdmin();
        Session agent = person(admin, Role.AGENT);
        String sub = linkByOidc(agent);

        JsonNode anonymized = PrivacySteps.anonymize(admin, agent.accountId());

        assertThat(anonymized.path("oidcLinked").isBoolean()).as("oidcLinked in %s", anonymized).isTrue();
        assertThat(anonymized.path("oidcLinked").booleanValue()).as("oidcLinked in %s", anonymized).isFalse();
        assertThat(linked(admin, agent.accountId())).as("oidcLinked of the anonymized account").isFalse();
        assertThat(PrivacySteps.entries(admin, "action", "ACCOUNT_ANONYMIZED", "targetAccountId",
                agent.accountId())).as("ACCOUNT_ANONYMIZED entries on the account").hasSize(1);
        assertThat(PrivacySteps.entries(admin, "action", "OIDC_UNLINKED", "targetAccountId", agent.accountId()))
                .as("OIDC_UNLINKED entries on the account").isEmpty();
        Invitation again = PrivacySteps.inviteAgain(admin, mail(), Role.AGENT, agent.email(), agent.name(),
                List.of());
        assertThat(again.accountId()).as("the account invited again").isNotEqualTo(agent.accountId());

        long signedIn = oidc.signedIn(console(), CONSOLE, sub, oidc.claims(agent.email()));

        assertThat(signedIn).as("account the old sub reaches").isEqualTo(again.accountId());
        assertThat(account(admin, again.accountId()).path("status").asString("")).as("status of the new account")
                .isEqualTo("ACTIVE");
        assertThat(linked(admin, again.accountId())).as("oidcLinked of the new account").isTrue();
        assertThat(linked(admin, agent.accountId())).as("oidcLinked of the anonymized account").isFalse();
    }
}
