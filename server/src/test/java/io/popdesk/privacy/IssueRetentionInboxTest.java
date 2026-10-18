package io.popdesk.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.TalkFlow;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.trash.TrashSteps;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The inbox items of an issue the cleanup moves (S10B-AC-22, conventions "Nothing is sent about an issue in the
 * trash"): as when an admin deletes it, they are kept, left out of the reporter's list and unread count, and listed
 * again after a restore. A live issue of the same reporter, with a staff answer, keeps its items in the list
 * throughout.
 *
 * <p>The waiting mail and webhook messages of S10B-AC-22 cannot be made for an issue that is due: a message is tried
 * for 24 hours, and a failed one is deleted 7 days later (docs/operate/configuration.md), while a {@code CLOSED} issue
 * takes no comment or change that would queue a new one. The report of this tab asks about it.
 */
class IssueRetentionInboxTest extends RetentionAppTest {

    @Test
    @DisplayName("S10B-AC-22 the reporter's inbox items about an issue the cleanup moved are kept, left out of the "
            + "list and the unread count, and listed again after the restore; the items of another issue stay listed")
    void inboxItemsAreHiddenAndComeBack() {
        Session admin = fixtures.admin(fixtures.signInAdmin());
        Company company = fixtures.createCompany(admin);
        Session reporter = fixtures.customer(admin, company.code());
        Session agent = fixtures.agent(admin, company.code());
        Issue gone = closedIssue(reporter, agent, company.code());
        Issue kept = fixtures.fileIssue(reporter, company.code());
        fixtures.comment(agent, kept.key(), "Staff answer on the live issue " + kept.key());
        List<Long> itemsOfGone = TalkFlow.ids(TalkFlow.inboxOf(reporter, gone.key()));
        List<Long> itemsOfKept = TalkFlow.ids(TalkFlow.inboxOf(reporter, kept.key()));
        long unread = TalkFlow.unreadCount(reporter);
        List<Map<String, Object>> rowsOfGone = rows.notifications(gone.key());
        assertThat(itemsOfGone).as("the reporter's items about %s before the move", gone.key()).isNotEmpty();
        assertThat(itemsOfKept).as("the reporter's items about %s", kept.key()).isNotEmpty();
        assertThat(unread).as("the reporter's unread count before the move").isGreaterThanOrEqualTo(
                itemsOfGone.size() + itemsOfKept.size());

        runAt(closedAt(agent, gone).plus(PERIOD).plus(Duration.ofDays(1)));
        Session moved = fixtures.signInAgain(reporter);
        assertThat(TrashSteps.entry(fixtures.signInAgain(admin), gone.key())).as("trash entry of %s", gone.key())
                .isNotNull();

        assertThat(TalkFlow.inboxOf(moved, gone.key())).as("the reporter's items about %s in the trash", gone.key())
                .isEmpty();
        assertThat(TalkFlow.unreadCount(moved)).as("the reporter's unread count while %s is in the trash", gone.key())
                .isEqualTo(unread - itemsOfGone.size());
        assertThat(rows.notifications(gone.key())).as("inbox rows about %s in the trash", gone.key())
                .isEqualTo(rowsOfGone);

        TrashSteps.restore(fixtures.signInAgain(admin), gone.key());
        Session restored = fixtures.signInAgain(reporter);
        assertThat(TalkFlow.ids(TalkFlow.inboxOf(restored, gone.key()))).as("the reporter's items about %s after the "
                + "restore", gone.key()).isEqualTo(itemsOfGone);
        assertThat(TalkFlow.ids(TalkFlow.inboxOf(restored, kept.key()))).as("the reporter's items about %s",
                kept.key()).isEqualTo(itemsOfKept);
        assertThat(TalkFlow.unreadCount(restored)).as("the reporter's unread count after the restore")
                .isEqualTo(unread);
    }
}
