package io.popdesk.ops;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.AppLog.Mark;
import io.popdesk.support.AppTest;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Invitation;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.NewIssue;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The log (PII-2, S0 premortem 4): one JSON object per line on standard output, emails masked after
 * the first character, and no passwords, tokens, invitation links, issue or comment content, or
 * secrets, also on the 409 paths where a unique violation carries the value.
 */
class LogPrivacyTest extends BareAppTest {

    private static final String SQL_EXCEPTION_HELPER = "org.hibernate.engine.jdbc.spi.SqlExceptionHelper";

    @Test
    @DisplayName("S0-AC-86 every line the app writes is one JSON object")
    void jsonLines() {
        Mark mark = log.mark();
        Session admin = fixtures.signInAdmin();
        admin.client().get("/api/console/issues/NOPE-999999").send();
        console().post("/api/public/sign-in").json(Map.of("email", Unique.email("nobody"), "password", "x")).send();
        fixtures.createCompany(admin);

        List<String> lines = log.lines(mark).stream().filter(l -> !l.isBlank()).toList();
        assertThat(lines).as("log lines since the mark").isNotEmpty();
        assertThat(log.jsonLines(mark)).as("JSON lines among %s", lines).hasSameSizeAs(lines);
    }

    @Test
    @DisplayName("S0-AC-86 after the flow and two 409s, no form of an email, password, token, link, or content "
            + "is in the log")
    void noSecretsAfterFlowAnd409s() {
        Session admin = fixtures.signInAdmin();
        String legalName = Unique.text("Logged Ltd.");
        Company company = fixtures.createCompany(admin, Unique.companyCode(), Unique.text("Logged"), legalName);
        String email = Unique.email("logged.person");
        Invitation invitation = fixtures.invite(admin, Role.CUSTOMER, email, Unique.text("Logged person"),
                List.of(company.code()));
        admin.client().post("/api/console/invitations")
                .json(Map.of("email", email, "name", "Again", "role", "CUSTOMER", "companyCodes",
                        List.of(company.code())))
                .expect(409);
        admin.client().post("/api/console/invitations")
                .json(Map.of("email", email.toUpperCase(java.util.Locale.ROOT), "name", "Again", "role", "CUSTOMER",
                        "companyCodes", List.of(company.code())))
                .expect(409);
        admin.client().post("/api/console/companies")
                .json(Map.of("code", company.code(), "name", "Again", "legalName", "Again Ltd."))
                .expect(409);
        Session customer = fixtures.accept(invitation);
        String content = "Line one\n\"quoted\" 내용 " + Unique.number() + " <b>&</b> /path";
        Issue issue = fixtures.fileIssue(customer, NewIssue.in(company.code()).content(content));
        String comment = "Comment \"" + Unique.number() + "\"\n두 번째 줄 / slash";
        fixtures.comment(customer, issue.key(), comment);
        Invitation agent = fixtures.invite(admin, Role.AGENT);
        String copied = admin.client().post("/api/console/invitations/" + agent.accountId() + "/link").expect(200)
                .jsonText("inviteLink");
        String wrong = "wrong-" + Unique.number() + "-Pass/word";
        console().post("/api/public/sign-in").json(Map.of("email", agent.email(), "password", wrong)).expect(401);

        String all = log.all();
        List<String> secrets = new ArrayList<>(List.of(email, agent.email(), Fixtures.PASSWORD,
                AppTest.ADMIN_PASSWORD, wrong, invitation.link(), invitation.token(), agent.link(), agent.token(),
                copied, Fixtures.tokenOf(copied), content, comment));
        assertThat(LogForms.foundIn(all, secrets)).as("secrets in the log").isEmpty();
        assertThat(all).as("the masked part of %s", email).doesNotContain(LogForms.afterFirstCharacter(email));
        assertThat(all).as("the masked part of %s", agent.email())
                .doesNotContain(LogForms.afterFirstCharacter(agent.email()));
    }

    @Test
    @DisplayName("S0-AC-86 Hibernate's SqlExceptionHelper does not log at WARN")
    void sqlExceptionHelperWarnOff() {
        Logger helper = LoggerFactory.getLogger(SQL_EXCEPTION_HELPER);

        assertThat(helper.isWarnEnabled()).as("WARN of %s", SQL_EXCEPTION_HELPER).isFalse();
    }

    @Test
    @DisplayName("S0-AC-86 the last line of defense masks an email in the message and in the stack trace")
    void outputMasksEmails() {
        String email = Unique.email("probe.person");
        String marker = "ops-probe-" + Unique.number();
        Mark mark = log.mark();

        LoggerFactory.getLogger("io.popdesk.ops.LogProbe").warn("{} contact {}", marker, email,
                new IllegalStateException("failed for " + email, new IllegalArgumentException("cause " + email)));

        String line = log.awaitLine(mark, marker);
        String since = log.since(mark);
        assertThat(log.jsonLines(mark)).as("JSON lines since the mark")
                .anySatisfy(n -> assertThat(n.toString()).contains(marker).contains("IllegalStateException"));
        assertThat(since).as("log since the probe").doesNotContain(email)
                .doesNotContain(LogForms.afterFirstCharacter(email));
        assertThat(line).as("the probe line").doesNotContain(email);
    }
}
