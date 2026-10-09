package io.popdesk.notify;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.notify.TableActivity.Counts;
import io.popdesk.support.AppLog;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Invitation;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import io.popdesk.support.Wait;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Without SMTP the app sends no mail and writes one log line per mail, with the address masked
 * after its first character. The people here get addresses that start with {@code q}, a letter no
 * other address in this context starts with, so a masked address {@code q***} in the log is theirs.
 * The mask character is taken to be {@code *} (or {@code •}, {@code …}).
 */
class NoSmtpMailLogTest extends BareAppTest {

    private static final Pattern MASKED_Q = Pattern.compile("(?<![A-Za-z0-9])q[*•…]+");

    private TableActivity activity;

    @BeforeEach
    void setUp() {
        activity = new TableActivity(db);
    }

    /** Lines since {@code mark} that hold a masked address starting with q. */
    private List<String> maskedLines(AppLog.Mark mark) {
        return log.lines(mark).stream().filter(l -> MASKED_Q.matcher(l).find()).toList();
    }

    private Invitation inviteQ(Session admin, Role role, String... codes) {
        return fixtures.invite(admin, role, Unique.email("q"), Unique.text("Quinn"), List.of(codes));
    }

    private static List<String> secretForms(Invitation invitation) {
        String link = invitation.link();
        return List.of(invitation.token(), link, URLEncoder.encode(link, StandardCharsets.UTF_8),
                link.replace("/", "\\/"), invitation.email(),
                invitation.email().replace("@", "%40"), invitation.email().substring(0, invitation.email().indexOf('@')));
    }

    @Test
    @DisplayName("S0-AC-73 without SMTP an invitation writes one log line with the address masked after its first character, and no link or token")
    void invitationLogLine() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        AppLog.Mark mark = log.mark();

        Invitation invitation = inviteQ(admin, Role.CUSTOMER, company.code());

        Wait.until("a log line with the masked address q***", () -> maskedLines(mark).isEmpty() ? null : mark);
        Wait.holds("one log line for one mail", Duration.ofSeconds(2), () -> maskedLines(mark).size() == 1);
        String all = log.all();
        for (String secret : secretForms(invitation)) {
            assertThat(all).as("the whole log").doesNotContainIgnoringCase(secret);
        }
    }

    @Test
    @DisplayName("S0-AC-73 without SMTP a staff answer writes one log line for the customer's mail, the address masked")
    void answerLogLine() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        AppLog.Mark invited = log.mark();
        Invitation invitation = inviteQ(admin, Role.CUSTOMER, company.code());
        Wait.until("the invitation's log line", () -> maskedLines(invited).isEmpty() ? null : invited);
        Session customer = fixtures.accept(invitation);
        Session agent = fixtures.agent(admin, company.code());
        Issue issue = fixtures.fileIssue(customer, company.code());
        AppLog.Mark mark = log.mark();

        fixtures.comment(agent, issue.key(), "Answer " + Unique.number());

        Wait.until("a log line with the masked address q***", () -> maskedLines(mark).isEmpty() ? null : mark);
        Wait.holds("one log line for one mail", Duration.ofSeconds(2), () -> maskedLines(mark).size() == 1);
        assertThat(log.since(mark)).doesNotContainIgnoringCase(customer.email())
                .doesNotContain(customer.email().replace("@", "%40"));
    }

    @Test
    @DisplayName("S0-AC-73 without SMTP the mail row is deleted once its log line is written")
    void mailRowDeleted() {
        Session admin = fixtures.signInAdmin();
        AppLog.Mark mark = log.mark();
        Map<String, Counts> before = activity.counts();

        inviteQ(admin, Role.AGENT);

        Wait.until("a log line with the masked address q***", () -> maskedLines(mark).isEmpty() ? null : mark);
        Map<String, Counts> queue = Wait.until("a table that got a row and lost it again", Duration.ofSeconds(25),
                () -> {
                    Map<String, Counts> found = TableActivity.insertedAndDeleted(before, activity.counts());
                    return found.isEmpty() ? null : found;
                });
        assertThat(queue).as("tables where a row came and went").hasSize(1);
        String table = queue.keySet().iterator().next();
        Wait.untilAsserted("the queue table back to its rows before the invitation", () ->
                assertThat(activity.counts().get(table).rows()).isEqualTo(before.get(table).rows()));
    }
}
