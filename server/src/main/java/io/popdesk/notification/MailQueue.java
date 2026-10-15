package io.popdesk.notification;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Queues an email in the outbox (architecture.md "Outbox", MAIL-5). Helpdesk calls this inside the
 * transaction of the change that causes the email, so the change and the queue row are kept or lost
 * together (S0-AC-33, S0-AC-66). The row holds only who receives it and what it is about; the address,
 * the language, the subject, and the link are read when it is sent.
 */
public interface MailQueue {

    /**
     * Whether email is sent: {@code SMTP_HOST} is set and this is no sandbox without an address. When it
     * is not, popdesk logs a line per email.
     */
    boolean enabled();

    /**
     * Queues one email in the caller's transaction; fails when there is none.
     *
     * @param kind what the email is about
     * @param recipientAccountId the account that receives it
     * @param issueId the issue it is about, if any
     * @param commentId the comment it is about, if any
     */
    void queue(MailKind kind, long recipientAccountId, @Nullable UUID issueId, @Nullable Long commentId);

    /**
     * Queues one email about a task of an issue in the caller's transaction; fails when there is none.
     * What it says about the task is read when it is sent (S6B-AC-34).
     *
     * @param kind what the email is about
     * @param recipientAccountId the account that receives it
     * @param issueId the issue the task belongs to
     * @param taskId the task it is about
     */
    void queueAboutTask(MailKind kind, long recipientAccountId, UUID issueId, long taskId);

    /**
     * Queues one email about a company and no issue in the caller's transaction; fails when there is
     * none. The row keeps the company's code, since what the email is about may be gone by the time it
     * is sent (S4-AC-51).
     *
     * @param kind what the email is about
     * @param recipientAccountId the account that receives it
     * @param companyCode the company it is about
     */
    void queueAboutCompany(MailKind kind, long recipientAccountId, String companyCode);
}
