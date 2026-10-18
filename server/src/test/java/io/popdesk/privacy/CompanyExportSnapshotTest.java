package io.popdesk.privacy;

import static io.popdesk.privacy.CompanyExportSteps.awaitEnded;
import static io.popdesk.privacy.CompanyExportSteps.download;
import static io.popdesk.privacy.CompanyExportSteps.id;
import static io.popdesk.privacy.CompanyExportSteps.only;
import static io.popdesk.privacy.CompanyExportSteps.start;
import static io.popdesk.privacy.CompanyExportSteps.state;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.TalkFlow;
import io.popdesk.privacy.CompanyExportSteps.Zip;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.Concurrently;
import io.popdesk.support.Fixtures.Comment;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.NewIssue;
import io.popdesk.support.Fixtures.Session;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Lists of the ZIP come from one snapshot (S10B-AC-17), in the weak form the task allows: there is no way, without a
 * fixed wait, to know that the export is reading its lists right now. So while the export runs, the customer changes
 * the issue's title and then its comment again and again, in steps {@code n = 1, 2, ...}: title {@code T-n}, then
 * comment {@code C-n}. Every state the database went through has the comment at the title's step or one before it.
 * The ZIP must show one such state, each value whole: an export that reads the issue and the comments in different
 * transactions can show a comment of a later step than the title.
 */
class CompanyExportSnapshotTest extends CompanyExportAppTest {

    private static String title(String base, int step) {
        return base + " T-" + step;
    }

    private static String content(String base, int step) {
        return base + " C-" + step;
    }

    /** The step of a value written as {@code base + " T-n"} or {@code base + " C-n"}; fails for any other value. */
    private static int stepOf(String value, String base, String mark) {
        String prefix = base + " " + mark + "-";
        assertThat(value).as("a whole value of step n").startsWith(prefix).matches(".*-\\d+");
        return Integer.parseInt(value.substring(prefix.length()));
    }

    @Test
    @DisplayName("S10B-AC-17 an issue and its comment changed again and again while the export runs are in the ZIP as "
            + "one state they had together, never a comment newer than the issue (weak form, no way to hit the "
            + "export's reading on purpose)")
    void changesWhileExportingAreNotMixed() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Session customer = fixtures.customer(admin, company.code());
        String base = "Snapshot " + company.code();
        Issue issue = fixtures.fileIssue(customer, NewIssue.in(company.code()).title(title(base, 0)));
        Comment comment = fixtures.comment(customer, issue.key(), content(base, 0));
        AtomicBoolean stop = new AtomicBoolean();
        AtomicInteger steps = new AtomicInteger();
        CompletableFuture<Void> changes = CompletableFuture.runAsync(() -> {
            for (int n = 1; !stop.get() && n <= 10_000; n++) {
                flow.edit(customer, issue.key(), Map.of("title", title(base, n)));
                TalkFlow.editContent(customer, issue.key(), comment.id(), content(base, n));
                steps.set(n);
            }
        });
        String id;
        try {
            id = id(start(admin, company.code(), false, false));
            assertThat(state(awaitEnded(admin, company.code(), id))).as("state at the end").isEqualTo("DONE");
        } finally {
            stop.set(true);
            Concurrently.finish(changes.thenApply(v -> (ApiResponse) null), "the changes of the issue");
        }

        Zip zip = download(admin, id);

        int titleStep = stepOf(zip.issue(issue.key()).path("title").asString(""), base, "T");
        JsonNode inZip = CompanyExportSteps.objects(zip.comments(issue.key())).stream()
                .filter(o -> o.path("content").asString("").startsWith(base + " C-")).findFirst()
                .orElseThrow(() -> new AssertionError("No comment of " + base + " in the ZIP"));
        int commentStep = stepOf(inZip.path("content").asString(""), base, "C");
        assertThat(commentStep).as("step of the comment (title at step %d, %d steps written)", titleStep,
                steps.get()).isBetween(titleStep - 1, titleStep);
        only(zip.comments(issue.key()), "content", inZip.path("content").asString(""));
    }
}
