package io.popdesk.mailin;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.MailInServer;
import io.popdesk.support.MailInServer.Folder;
import io.popdesk.support.ReceivedMail;
import io.popdesk.support.Unique;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;

/** Which company a new issue by mail goes to (MAILIN-3, D-16), and notice A when there is none (TM-36). */
class CompanyChoiceTest extends MailInAppTest {

    private static final String NOTICE = "notice";

    /** {@code code} with every second letter in lower case: {@code C00AB} gives {@code C00aB}. */
    static String mixedCase(String code) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < code.length(); i++) {
            String c = code.substring(i, i + 1);
            out.append(i % 2 == 1 ? c.toLowerCase(Locale.ROOT) : c);
        }
        return out.toString();
    }

    static Stream<Arguments> addresses() {
        return Stream.of(
                Arguments.of("+code in To, mixed case", 2, (BiFunction<Inbound, String, Inbound>) (m, b) ->
                        m.to("Support+" + mixedCase(b) + "@Popdesk.Test"), "B"),
                Arguments.of("+code only in Cc", 2, (BiFunction<Inbound, String, Inbound>) (m, b) ->
                        m.to(MailInServer.ADDRESS).cc("support+" + b + "@popdesk.test"), "B"),
                Arguments.of("+code only in a display name", 2, (BiFunction<Inbound, String, Inbound>) (m, b) ->
                        m.to("\"support+" + b + "@popdesk.test\" <" + MailInServer.ADDRESS + ">"), NOTICE),
                Arguments.of("+code at another domain", 2, (BiFunction<Inbound, String, Inbound>) (m, b) ->
                        m.to("support+" + b + "@other.example").cc(MailInServer.ADDRESS), NOTICE),
                Arguments.of("one company, no code", 1, (BiFunction<Inbound, String, Inbound>) (m, b) ->
                        m.to(MailInServer.ADDRESS), "A"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("addresses")
    @DisplayName("S12-AC-19 the company is the +code of To or Cc at MAIL_INBOUND_ADDRESS (not in a display name, not at another domain), else the sender's one company")
    void companyFromAddress(String what, int memberships, BiFunction<Inbound, String, Inbound> address,
            String expected) {
        Company a = company();
        Company b = company();
        Session sender = memberships == 2 ? customer(a.code(), b.code()) : customer(a.code());
        Inbound mail = address.apply(mailFrom(sender), b.code()).text("Body " + Unique.number());

        send(mail);
        rounds.next();

        if (expected.equals(NOTICE)) {
            awaitMailTo(sender, NOTICE_A_SUBJECT);
            assertThat(issuesOf(sender)).as("issues of the sender, " + what).isEmpty();
        } else {
            JsonNode issue = onlyIssueOf(sender);
            assertThat(issue.path("companyCode").asString("")).as("company of the issue, " + what)
                    .isEqualTo(expected.equals("A") ? a.code() : b.code());
            assertThat(mailTo(sender, NOTICE_A_SUBJECT)).as("notice A, " + what).isEmpty();
        }
        assertThat(foldersOf(mail)).as("folders of the mail, " + what).containsExactly(Folder.PROCESSED);
    }

    /** One way of having no company to file in: who sends, and the To address. */
    private record Case(String what, Session sender, String to) {}

    private void companyStatus(String code, String status) {
        admin.client().put("/api/console/companies/" + code + "/status").json(Map.of("status", status)).expect(200);
    }

    @Test
    @DisplayName("S12-AC-21 two companies and no code, a code not the sender's, a code of no company, a suspended or archived own company, no company: no issue, and one same notice A without keys or codes")
    void noCompanyGivesOneNoticeA() {
        Company a = company();
        Company b = company();
        Company notMine = company();
        Company suspended = company();
        Company archived = company();
        Company left = company();
        String noCompany = Unique.companyCode();
        List<Case> cases = new ArrayList<>(List.of(
                new Case("two companies, no code", customer(a.code(), b.code()), MailInServer.ADDRESS),
                new Case("a code not the sender's", customer(a.code()), "support+" + notMine.code() + "@popdesk.test"),
                new Case("a code of no company", customer(a.code()), "support+" + noCompany + "@popdesk.test"),
                new Case("a suspended company", customer(suspended.code()), MailInServer.ADDRESS),
                new Case("an archived company", customer(archived.code()), MailInServer.ADDRESS)));
        Session alone = customer(left.code());
        admin.client().delete("/api/console/companies/" + left.code() + "/customers/" + alone.accountId()).expect(200);
        cases.add(new Case("no company", alone, MailInServer.ADDRESS));
        companyStatus(suspended.code(), "SUSPENDED");
        companyStatus(archived.code(), "ARCHIVED");
        List<String> codes = List.of(a.code(), b.code(), notMine.code(), suspended.code(), archived.code(),
                left.code(), noCompany);

        List<Inbound> mails = new ArrayList<>();
        for (Case c : cases) {
            Inbound mail = mailFrom(c.sender()).to(c.to()).text("Body " + Unique.number());
            send(mail);
            mails.add(mail);
        }
        rounds.next();

        Set<String> notices = new LinkedHashSet<>();
        for (int i = 0; i < cases.size(); i++) {
            Case c = cases.get(i);
            ReceivedMail notice = awaitMailTo(c.sender(), NOTICE_A_SUBJECT);
            notices.add(withoutRecipient(notice, c.sender()));
            for (String code : codes) {
                assertThat(notice.text()).as("notice A of " + c.what()).doesNotContain(code);
            }
            assertThat(issuesOf(c.sender())).as("issues of the sender, " + c.what()).isEmpty();
            assertThat(foldersOf(mails.get(i))).as("folders, " + c.what()).containsExactly(Folder.PROCESSED);
        }
        assertThat(notices).as("notice A of the six cases, without name and email").hasSize(1);
    }
}
