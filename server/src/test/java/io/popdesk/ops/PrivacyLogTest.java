package io.popdesk.ops;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.privacy.ExportSteps;
import io.popdesk.privacy.PrivacyAppTest;
import io.popdesk.privacy.PrivacySteps;
import io.popdesk.support.AppLog.Mark;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.File;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.NewIssue;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The log of an export and an anonymization (S10A-AC-26, PII-2, TM-30): no form of the person's email, name, or the
 * content of their issues and comments ({@link LogForms}), from before or after the anonymization. It extends
 * {@link PrivacyAppTest} because it anonymizes, and lives in this package for {@link LogForms}. SMTP is on there, so
 * no mail is a log line.
 */
class PrivacyLogTest extends PrivacyAppTest {

    @Test
    @DisplayName("S10A-AC-26 exporting and anonymizing a customer leave no form of their email, name, or issue and "
            + "comment content in the log")
    void exportAndAnonymizationLeaveNoPersonalText() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        String word = IssueFlow.word();
        String name = "Customer " + word;
        Session customer = fixtures.accept(fixtures.invite(admin, Role.CUSTOMER, word + "@example.test", name,
                List.of(company.code())));
        String title = "Title " + IssueFlow.word();
        String content = "Content \"" + IssueFlow.word() + "\" /\n" + IssueFlow.koreanWord();
        Issue issue = fixtures.fileIssue(customer, NewIssue.in(company.code()).title(title).content(content));
        String comment = "Comment " + IssueFlow.word() + " " + IssueFlow.koreanWord();
        fixtures.comment(customer, issue.key(), comment);
        fixtures.attach(customer, issue.key(), File.text("log-" + Unique.number() + ".txt", "File"));
        List<String> values = new ArrayList<>(List.of(customer.email(), word, name, title, content, comment));
        Mark mark = log.mark();

        ExportSteps.headRequest(admin, customer.accountId()).expect(200);
        ExportSteps.export(admin, customer.accountId());
        PrivacySteps.anonymize(admin, customer.accountId());
        ExportSteps.export(admin, customer.accountId());

        String probe = "privacy-log-probe-" + Unique.number();
        LoggerFactory.getLogger("io.popdesk.ops.LogProbe").warn(probe);
        log.awaitLine(mark, probe);
        String since = log.since(mark);
        assertThat(since.lines().filter(line -> !line.isBlank()).count()).as("log lines since the mark").isPositive();
        assertThat(LogForms.foundIn(since, values)).as("email, name, and content in the log").isEmpty();
    }
}
