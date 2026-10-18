package io.popdesk.privacy;

import static io.popdesk.privacy.CompanyExportSteps.zipOf;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.link.LinkSteps;
import io.popdesk.privacy.CompanyExportSteps.Zip;
import io.popdesk.support.Fixtures.Attachment;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.File;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.NewIssue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import io.popdesk.trash.TrashSteps;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The ZIP holds one company (S10B-AC-16): two companies with a customer of both, files of the same name, and issues
 * linked across them; private issues only with {@code includePrivate}; no issue in the trash or purged. Every path and
 * every JSON text of the ZIP, and the bytes of every file, are looked through.
 */
class CompanyExportBoundaryTest extends CompanyExportAppTest {

    private static final String SAME_NAME = "same-name.txt";

    /** Company A with what goes into its ZIP, and what must not. */
    private record World(Session admin, Company a, Company b, Issue ofA, Issue privateOfA, Issue trashed,
            Issue purged, Issue ofB, Issue ofBByBoth, String contentOfB, Attachment fileOfB) {}

    private World world() {
        Session admin = fixtures.signInAdmin();
        Company a = fixtures.createCompany(admin);
        Company b = fixtures.createCompany(admin);
        Session ofA = fixtures.customer(admin, a.code());
        Session ofBoth = fixtures.customer(admin, a.code(), b.code());
        Session ofB = fixtures.customer(admin, b.code());
        Session agent = fixtures.agent(admin, a.code(), b.code());
        Issue issueOfA = fixtures.fileIssue(ofA, NewIssue.in(a.code()).file(File.text(SAME_NAME, "Of A")));
        Issue privateOfA = fixtures.fileIssue(ofBoth, NewIssue.in(a.code()).title(Unique.text("Private of A"))
                .privateIssue());
        Issue trashed = fixtures.fileIssue(ofA, NewIssue.in(a.code()).title(Unique.text("Trashed of A")));
        Issue purged = fixtures.fileIssue(ofA, NewIssue.in(a.code()).title(Unique.text("Purged of A")));
        String contentOfB = Unique.text("Bytes of B");
        Issue issueOfB = fixtures.fileIssue(ofB, NewIssue.in(b.code()).title(Unique.text("Of B")));
        Attachment fileOfB = fixtures.attach(ofB, issueOfB.key(), File.text(SAME_NAME, contentOfB));
        Issue byBoth = fixtures.fileIssue(ofBoth, NewIssue.in(b.code()).title(Unique.text("Of B by both")));
        fixtures.comment(ofBoth, byBoth.key(), Unique.text("Comment in B"));
        LinkSteps.link(agent, issueOfA.key(), issueOfB.key(), LinkSteps.RELATED);
        LinkSteps.link(agent, issueOfA.key(), byBoth.key(), LinkSteps.DUPLICATE_OF);
        TrashSteps.delete(admin, trashed.key());
        TrashSteps.delete(admin, purged.key());
        TrashSteps.purge(admin, purged.key());
        return new World(admin, a, b, issueOfA, privateOfA, trashed, purged, issueOfB, byBoth, contentOfB, fileOfB);
    }

    private static String title(Issue issue) {
        return issue.json().path("title").asString("");
    }

    /** Fails if any path, JSON text, or file of the ZIP holds one of {@code texts}. */
    private static void assertNowhere(Zip zip, List<String> texts) {
        String all = zip.allText();
        List<String> found = new ArrayList<>();
        for (String t : texts) {
            if (all.contains(t)) {
                found.add(t + " in a path or JSON");
            }
            zip.files().forEach((path, bytes) -> {
                if (new String(bytes, StandardCharsets.UTF_8).contains(t)) {
                    found.add(t + " in " + path);
                }
            });
        }
        assertThat(found).as("texts of company B, the trash, and private issues in the ZIP of company A").isEmpty();
    }

    @Test
    @DisplayName("S10B-AC-16 the ZIP of company A holds A's issues only: no issue, key, title, link, file, or code of "
            + "company B, also through a customer of both and linked issues, no private issue without "
            + "includePrivate, and no issue in the trash or purged")
    void zipHoldsNothingOfTheOtherCompany() {
        World w = world();

        Zip zip = zipOf(w.admin(), w.a().code(), true, false);

        assertThat(zip.issueKeys()).as("issues in the ZIP").containsExactly(w.ofA().key());
        assertThat(zip.filesOf(w.ofA().key())).as("files of %s", w.ofA().key()).hasSize(1);
        assertNowhere(zip, List.of(w.b().code(), w.ofB().key(), title(w.ofB()), w.ofBByBoth().key(),
                title(w.ofBByBoth()), w.contentOfB(), w.fileOfB().id(), w.privateOfA().key(), title(w.privateOfA()),
                w.trashed().key(), title(w.trashed()), w.purged().key(), title(w.purged())));
    }

    @Test
    @DisplayName("S10B-AC-16 with includePrivate the private issue of the company is in the ZIP, still nothing of "
            + "the other company or the trash")
    void privateIssuesWhenAsked() {
        World w = world();

        Zip zip = zipOf(w.admin(), w.a().code(), false, true);

        assertThat(zip.issueKeys()).as("issues in the ZIP").containsExactlyInAnyOrder(w.ofA().key(),
                w.privateOfA().key());
        assertThat(zip.issue(w.privateOfA().key()).path("private").asBoolean(false)).as("private").isTrue();
        assertNowhere(zip, List.of(w.b().code(), w.ofB().key(), w.ofBByBoth().key(), w.contentOfB(),
                w.trashed().key(), w.purged().key()));
    }
}
