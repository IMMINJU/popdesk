package io.popdesk.project;

import static io.popdesk.project.InvitationTest.invite;
import static io.popdesk.project.ProjectChecks.assertFieldError;
import static io.popdesk.project.ProjectChecks.pending;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The length of an invited email (ACCT-1): 100 characters at most. */
class InvitationEmailLengthTest extends BareAppTest {

    /** An address of {@code length} characters: a local part of 40, then a domain under {@code example.test}. */
    private static String email(int length) {
        String local = ("long-" + Unique.number() + "-" + "a".repeat(40)).substring(0, 40);
        String tail = ".example.test";
        String label = "d".repeat(length - local.length() - 1 - tail.length());
        String email = local + "@" + label + tail;
        assertThat(email).as("the test address").hasSize(length);
        return email;
    }

    @Test
    @DisplayName("S1A-AC-72 an invitation to an email of exactly 100 characters is 201 and the account is pending")
    void hundredCharacters() {
        Session admin = fixtures.signInAdmin();
        String email = email(100);

        ApiResponse created = invite(admin, email, "Long address", "AGENT", List.of());

        assertThat(created.status()).as("%s: %s", created, created.snippet()).isEqualTo(201);
        assertThat(pending(admin, email)).as("pending invitations of %s", email).hasSize(1);
    }

    @Test
    @DisplayName("S1A-AC-72 an invitation to an email of 101 characters is 400 with field email TOO_LONG and makes "
            + "no account")
    void hundredAndOneCharacters() {
        Session admin = fixtures.signInAdmin();
        String email = email(101);

        ApiResponse refused = invite(admin, email, "Too long address", "AGENT", List.of());

        assertFieldError(refused, 400, "email", "TOO_LONG");
        assertThat(pending(admin, email)).as("pending invitations of %s", email).isEmpty();
    }
}
