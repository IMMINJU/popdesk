package io.popdesk.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiResponse;
import io.popdesk.support.AppLog;
import io.popdesk.support.ConnectedAppTest;
import io.popdesk.support.Fixtures;
import io.popdesk.support.Fixtures.Session;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The reset token: where it sits in the link, what the database keeps, and where it must never show. */
class PasswordResetTokenTest extends ConnectedAppTest {

    @Test
    @DisplayName("S1A-AC-08 the token stands for 32 random bytes, differs per request, and is only in the link's fragment")
    void tokenIs32BytesInTheFragment() {
        Session admin = fixtures.signInAdmin();
        Session customer = fixtures.customer(admin, fixtures.createCompany(admin).code());
        Session agent = fixtures.agent(admin);

        URI toCustomer = URI.create(PasswordSteps.requestLink(portal(), mail(), customer.email()));
        URI toAgent = URI.create(PasswordSteps.requestLink(console(), mail(), agent.email()));

        for (URI link : List.of(toCustomer, toAgent)) {
            assertThat(link.getRawPath()).as("path of %s", link).isEqualTo("/reset-password");
            assertThat(link.getRawQuery()).as("query of %s", link).isNull();
            assertThat(link.getRawFragment()).as("fragment of %s", link).startsWith("token=");
            String token = Fixtures.tokenOf(link.toString());
            assertThat(link.getRawFragment()).isEqualTo("token=" + token);
            assertThat(PasswordSteps.tokenBytes(token)).as("token %s as base64url or hex", token)
                    .hasValueSatisfying(bytes -> assertThat(bytes).hasSize(32));
        }
        assertThat(Fixtures.tokenOf(toCustomer.toString())).isNotEqualTo(Fixtures.tokenOf(toAgent.toString()));
    }

    @Test
    @DisplayName("S1A-AC-08, S1A-AC-14 no table keeps the token itself, before or after it is used; "
            + "the database has its SHA-256")
    void databaseKeepsOnlyTheHash() {
        Session admin = fixtures.signInAdmin();
        Session customer = fixtures.customer(admin, fixtures.createCompany(admin).code());

        String token = PasswordSteps.requestToken(portal(), mail(), customer.email());

        assertThat(rowsWithAnyOf(PasswordSteps.tokenForms(token))).as("rows holding the token").isEmpty();
        assertThat(rowsWithAnyOf(PasswordSteps.hashForms(token))).as("rows holding the token's SHA-256").isNotEmpty();
        PasswordSteps.confirm(portal(), token, PasswordSteps.NEW_PASSWORD).expectStatus(204);
        assertThat(rowsWithAnyOf(PasswordSteps.tokenForms(token))).as("rows holding the used token").isEmpty();
    }

    @Test
    @DisplayName("S1A-AC-14 responses of the reset calls have Cache-Control: no-store, and the log has neither "
            + "the token nor the link")
    void noStoreAndNothingInTheLog() {
        Session admin = fixtures.signInAdmin();
        Session customer = fixtures.customer(admin, fixtures.createCompany(admin).code());
        AppLog.Mark mark = log.mark();

        ApiResponse requested = PasswordSteps.request(portal(), customer.email());
        requested.expectStatus(202);
        String link = PasswordSteps.linkIn(PasswordSteps.awaitMails(mail(), customer.email(), 1).getFirst().text());
        String token = Fixtures.tokenOf(link);
        ApiResponse refused = PasswordSteps.confirm(portal(), token, "short");
        ApiResponse done = PasswordSteps.confirm(portal(), token, PasswordSteps.NEW_PASSWORD);
        ApiResponse again = PasswordSteps.confirm(portal(), token, PasswordSteps.NEW_PASSWORD);

        refused.expectStatus(400);
        done.expectStatus(204);
        again.expectStatus(400);
        for (ApiResponse response : List.of(requested, refused, done, again)) {
            assertThat(response.headers("Cache-Control")).as("Cache-Control of %s", response)
                    .anySatisfy(value -> assertThat(value.toLowerCase(Locale.ROOT)).contains("no-store"));
        }
        String written = log.since(mark);
        for (String form : PasswordSteps.tokenForms(token)) {
            assertThat(written).as("the log since the request").doesNotContain(form);
        }
        assertThat(written).as("the log since the request").doesNotContain("reset-password#");
    }

    private List<Object> rowsWithAnyOf(List<String> forms) {
        return forms.stream().flatMap(form -> AuthChecks.rowsContaining(db, form).stream()).map(Object.class::cast)
                .toList();
    }
}
