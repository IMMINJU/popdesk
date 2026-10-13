package io.popdesk.attachment;

import static io.popdesk.attachment.AttachmentSteps.bytes;
import static io.popdesk.attachment.AttachmentSteps.path;
import static io.popdesk.attachment.ImageSteps.attachmentHistory;
import static io.popdesk.attachment.ImageSteps.uploaded;
import static io.popdesk.issue.TalkFlow.body;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.attachment.ImageSteps.Stage;
import io.popdesk.issue.IssueFlow;
import io.popdesk.issue.TalkFlow;
import io.popdesk.support.ConnectedAppTest;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Unique;
import io.popdesk.support.Wait;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Attachments are written into the history and told to nobody (ATT-8): no inbox item, no mail, no
 * webhook request. "Nothing came" is read after something later did come: an invitation mail, which
 * leaves the mail queue after everything queued before it, and the webhook of an issue filed
 * afterwards.
 */
class AttachmentHistoryQuietTest extends ConnectedAppTest {

    /** What the reporter, the assignee, and the webhook receiver got about one issue. */
    private record Told(List<Long> reporterInbox, List<Long> assigneeInbox, int mailToReporter, int mailToAssignee,
            int webhooks) {}

    /** Waits for an invitation mail and for the webhook of a new issue, then reads what came about {@code st}'s issue. */
    private Told told(Stage st) {
        TalkFlow.awaitALaterMail(fixtures, st.admin());
        Issue later = fixtures.fileIssue(st.customer(), st.code());
        Wait.until("the webhook of the issue filed afterwards, " + later.key(),
                () -> webhook().containing(later.key()).isEmpty() ? null : Boolean.TRUE);
        return new Told(TalkFlow.ids(TalkFlow.inboxOf(st.customer(), st.key())),
                TalkFlow.ids(TalkFlow.inboxOf(st.agent(), st.key())), mail().messagesTo(st.customer().email()).size(),
                mail().messagesTo(st.agent().email()).size(), webhook().containing(st.key()).size());
    }

    @Test
    @DisplayName("S5-AC-44 uploading, attaching an image, and deleting attachments leave history entries and no "
            + "inbox item, mail, or webhook request for the reporter or the assignee")
    void attachmentsAreToldToNobody() {
        Stage st = ImageSteps.stage(fixtures);
        IssueFlow.assignRequest(st.agent(), st.key(), st.agent().accountId()).expect(200);
        Told before = told(st);

        String byAgent = AttachmentSteps.upload(st.agent(), st.key(), "agent.bin", bytes(100, Unique.number()))
                .expectStatus(201).jsonText("id");
        String byCustomer = AttachmentSteps.upload(st.customer(), st.key(), "customer.bin",
                bytes(100, Unique.number())).expectStatus(201).jsonText("id");
        String image = uploaded(st.customer());
        IssueFlow.editRequest(st.customer(), st.key(), body("version", ImageSteps.version(st.customer(), st.key()),
                "imageIds", List.of(image))).expect(200);
        st.agent().client().delete(path(st.agent(), st.key(), byAgent)).expect(204);
        st.customer().client().delete(path(st.customer(), st.key(), byCustomer)).expect(204);
        Told after = told(st);

        assertThat(attachmentHistory(st.agent(), st.key())).as("ATTACHMENT entries: three added, two deleted")
                .hasSize(5);
        assertThat(after).as("what was told about the issue, before and after").isEqualTo(before);
    }
}
