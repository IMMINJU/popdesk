package io.popdesk.notification.outbox;

import io.popdesk.notification.IssueFacts.Issue;

/**
 * The subject and text of each email, in English and Korean (MAIL-1). Mail to customers names staff
 * only as the support team (D-49, S0-AC-70) and holds no comment text. Values in the subject get their
 * line breaks as spaces (S0-AC-71).
 */
final class MailText {

    /**
     * One email's subject and plain text, and the name it comes from: the display name of the branding
     * (S9-AC-26), or null for the address alone.
     */
    record Message(String subject, String text, String senderName) {

        Message(String subject, String text) {
            this(subject, text, null);
        }

        /** The same email from {@code senderName}. */
        Message from(String senderName) {
            return new Message(subject, text, senderName);
        }
    }

    private MailText() {}

    /**
     * An invitation to the person's app (S0-AC-72), naming the installation by its display name
     * (S9-AC-27).
     */
    static Message invitation(String locale, String displayName, String name, String link) {
        String desk = oneLine(displayName);
        if (korean(locale)) {
            return new Message(desk + "에 초대되었습니다",
                    name + "님, " + desk + "에 초대되었습니다.\n\n"
                            + "아래 링크에서 비밀번호를 정하면 로그인할 수 있습니다. 링크는 일주일 동안 쓸 수 있습니다.\n\n"
                            + link + "\n\n"
                            + "초대를 기다리지 않았다면 이 메일을 지워도 됩니다.\n");
        }
        return new Message("You are invited to " + desk,
                "Hello " + name + ",\n\n"
                        + "You are invited to " + desk + ". Set your password at the link below to sign in. The link works "
                        + "for one week.\n\n"
                        + link + "\n\n"
                        + "If you did not expect this invitation, you can delete this email.\n");
    }

    /**
     * A link to set a new password (S1A-AC-06). It names no other account and says nothing of the old
     * password; the installation is named by its display name (S9-AC-27).
     */
    static Message passwordReset(String locale, String displayName, String name, String link) {
        String desk = oneLine(displayName);
        if (korean(locale)) {
            return new Message(desk + " 비밀번호 재설정",
                    name + "님, " + desk + " 계정의 비밀번호 재설정 요청을 받았습니다.\n\n"
                            + "아래 링크에서 새 비밀번호를 정할 수 있습니다. 링크는 1시간 동안 쓸 수 있습니다.\n\n"
                            + link + "\n\n"
                            + "요청한 적이 없다면 이 메일을 지워도 됩니다.\n");
        }
        return new Message("Reset your " + desk + " password",
                "Hello " + name + ",\n\n"
                        + "A password reset was asked for your " + desk + " account. Set a new password at the link below. "
                        + "The link works for one hour.\n\n"
                        + link + "\n\n"
                        + "If you did not ask for this, you can delete this email.\n");
    }

    /** A public answer by staff, to the customer who filed the issue (S0-AC-70). */
    static Message staffComment(String locale, Issue issue, String link) {
        String title = oneLine(issue.title());
        if (korean(locale)) {
            return new Message("[" + issue.key() + "] 지원팀이 답변했습니다: " + title,
                    "지원팀이 문의 " + issue.key() + "에 답변했습니다.\n\n"
                            + "제목: " + title + "\n\n"
                            + "포털에서 답변을 보고 댓글을 달 수 있습니다.\n\n"
                            + link + "\n");
        }
        return new Message("[" + issue.key() + "] The support team answered: " + title,
                "The support team answered your issue " + issue.key() + ".\n\n"
                        + "Title: " + title + "\n\n"
                        + "Read the answer and reply in the portal:\n\n"
                        + link + "\n");
    }

    /**
     * An issue staff filed for the customer, to that customer (S3-AC-37). It says the support team filed
     * it and names nobody; it holds the title and not what the issue says.
     */
    static Message filedOnBehalf(String locale, Issue issue, String link) {
        String title = oneLine(issue.title());
        if (korean(locale)) {
            return new Message("[" + issue.key() + "] 지원팀이 문의를 대신 올렸습니다: " + title,
                    "지원팀이 고객님을 대신해 문의를 올렸습니다.\n\n"
                            + "문의: " + issue.key() + "\n"
                            + "제목: " + title + "\n\n"
                            + "포털에서 문의를 보고 댓글을 달 수 있습니다.\n\n"
                            + link + "\n");
        }
        return new Message("[" + issue.key() + "] The support team filed an issue for you: " + title,
                "The support team filed an issue for you.\n\n"
                        + "Issue: " + issue.key() + "\n"
                        + "Title: " + title + "\n\n"
                        + "Read it and reply in the portal:\n\n"
                        + link + "\n");
    }

    /** Staff resolved the issue, to the customer who filed it (S4-AC-48). */
    static Message resolved(String locale, Issue issue, String link) {
        return korean(locale)
                ? aboutIssue(locale, issue, link, "지원팀이 문의를 해결로 바꿨습니다",
                        "지원팀이 문의를 해결로 바꿨습니다.", "포털에서 답변을 보고 해결됐는지 확인해 주세요.")
                : aboutIssue(locale, issue, link, "The support team resolved your issue",
                        "The support team marked your issue as resolved.",
                        "Read the answer in the portal and check that it is solved:");
    }

    /** Staff closed the issue, to the customer who filed it (S4-AC-48). */
    static Message closed(String locale, Issue issue, String link) {
        return korean(locale)
                ? aboutIssue(locale, issue, link, "지원팀이 문의를 종료했습니다", "지원팀이 문의를 종료했습니다.",
                        "포털에서 문의를 볼 수 있습니다.")
                : aboutIssue(locale, issue, link, "The support team closed your issue",
                        "The support team closed your issue.", "Read it in the portal:");
    }

    /** Staff reopened the issue, to the customer who filed it (S4-AC-48). */
    static Message reopened(String locale, Issue issue, String link) {
        return korean(locale)
                ? aboutIssue(locale, issue, link, "지원팀이 문의를 다시 열었습니다", "지원팀이 문의를 다시 열었습니다.",
                        "포털에서 문의를 보고 댓글을 달 수 있습니다.")
                : aboutIssue(locale, issue, link, "The support team reopened your issue",
                        "The support team reopened your issue.", "Read it and reply in the portal:");
    }

    /**
     * A comment mentions the recipient (S4-AC-50). One text for a customer and for staff: it does not
     * say who wrote the comment, so it names no staff member to a customer (S4-AC-53).
     */
    static Message mentioned(String locale, Issue issue, String link) {
        return korean(locale)
                ? aboutIssue(locale, issue, link, "댓글에서 회원님을 언급했습니다", "문의의 댓글에서 회원님을 언급했습니다.",
                        "아래 링크에서 댓글을 볼 수 있습니다.")
                : aboutIssue(locale, issue, link, "You were mentioned in a comment",
                        "You were mentioned in a comment on an issue.", "Read the comment here:");
    }

    /** The recipient became the assignee of the issue (S4-AC-49). It does not say who chose them. */
    static Message assigned(String locale, Issue issue, String link) {
        return korean(locale)
                ? aboutIssue(locale, issue, link, "문의의 담당자로 정해졌습니다", "문의의 담당자로 정해졌습니다.",
                        "콘솔에서 문의를 볼 수 있습니다.")
                : aboutIssue(locale, issue, link, "You are the assignee now", "You were made the assignee of an issue.",
                        "Read it in the console:");
    }

    /**
     * The recipient became the assignee of a task of the issue (S6B-AC-34): the task's title in the
     * subject in place of the issue's, then the task, the issue's key and title, and the link to the task.
     * It does not say who chose them, nor anything a note says.
     *
     * @param taskTitle the task's title as it is when the email is sent
     * @param link the console's address of the task
     */
    static Message taskAssigned(String locale, Issue issue, String taskTitle, String link) {
        String task = oneLine(taskTitle);
        String title = oneLine(issue.title());
        if (korean(locale)) {
            return new Message("[" + issue.key() + "] 작업을 맡았습니다: " + task,
                    "작업을 맡았습니다.\n\n"
                            + "작업: " + task + "\n"
                            + "문의: " + issue.key() + "\n"
                            + "제목: " + title + "\n\n"
                            + "콘솔에서 작업을 볼 수 있습니다.\n\n"
                            + link + "\n");
        }
        return new Message("[" + issue.key() + "] Task assigned: " + task,
                "You were given a task on an issue.\n\n"
                        + "Task: " + task + "\n"
                        + "Issue: " + issue.key() + "\n"
                        + "Title: " + title + "\n\n"
                        + "Open the task in the console:\n\n"
                        + link + "\n");
    }

    /**
     * A goal of the issue passed its SLA due time, to staff (S8B-AC-16): the key and the goal in the subject, then
     * the key, the company code, the title, and the console link. Nothing the issue says and nothing of the
     * customer.
     *
     * @param firstResponse the first response goal; else the resolution goal
     */
    static Message slaBreached(String locale, Issue issue, boolean firstResponse, String link) {
        String title = oneLine(issue.title());
        if (korean(locale)) {
            String goal = firstResponse ? "첫 응답" : "해결";
            return new Message("[" + issue.key() + "] SLA " + goal + " 기한 넘김: " + title,
                    "문의의 " + goal + "이 SLA 기한을 넘겼습니다.\n\n"
                            + "문의: " + issue.key() + "\n"
                            + "고객사: " + oneLine(issue.companyCode()) + "\n"
                            + "제목: " + title + "\n\n"
                            + "콘솔에서 문의를 볼 수 있습니다.\n\n"
                            + link + "\n");
        }
        return new Message("[" + issue.key() + "] SLA " + (firstResponse ? "first response" : "resolution")
                        + " overdue: " + title,
                (firstResponse ? "The first response to" : "The resolution of") + " an issue is past its SLA due "
                        + "time.\n\n"
                        + "Issue: " + issue.key() + "\n"
                        + "Company: " + oneLine(issue.companyCode()) + "\n"
                        + "Title: " + title + "\n\n"
                        + "Read it in the console:\n\n"
                        + link + "\n");
    }

    /**
     * A customer manager asked for an existing account to join a company, to an admin (S4-AC-51). Only
     * the company's code: not the name and the email the manager typed, and not whose account it is.
     */
    static Message membershipRequested(String locale, String companyCode, String link) {
        String code = oneLine(companyCode);
        if (korean(locale)) {
            return new Message("고객사 " + code + "에 소속 요청이 있습니다",
                    "고객사 " + code + "의 담당자가 소속 요청을 보냈습니다.\n\n"
                            + "콘솔에서 요청을 보고 승인하거나 거절할 수 있습니다.\n\n"
                            + link + "\n");
        }
        return new Message("Membership request for company " + code,
                "A customer manager of company " + code + " asked to add a person to the company.\n\n"
                        + "Approve or reject the request in the console:\n\n"
                        + link + "\n");
    }

    /**
     * Notice A (S12-AC-29, screens.md): the email the customer sent did not become an issue. One text whatever the
     * reason: no issue's key or title, no company code (TM-36). No key in the subject either.
     *
     * @param inboundAddress {@code MAIL_INBOUND_ADDRESS}, whose local part and domain make the example; null for a
     *     generic one
     * @param link the portal's page to file an issue
     */
    static Message mailInNoIssue(String locale, String inboundAddress, String link) {
        boolean korean = korean(locale);
        String local = "support";
        String domain = "…";
        if (inboundAddress != null && inboundAddress.lastIndexOf('@') > 0) {
            local = oneLine(inboundAddress.substring(0, inboundAddress.lastIndexOf('@')));
            domain = oneLine(inboundAddress.substring(inboundAddress.lastIndexOf('@') + 1));
        }
        if (korean) {
            return new Message("메일로 문의를 만들지 못했습니다",
                    "보내 주신 메일로 문의를 만들지 못했습니다. 여러 고객사에 속해 있다면 받는 주소에 고객사 코드를 붙여 "
                            + "보내 주세요(예: " + local + "+코드@" + domain + "). 포털에서 바로 문의를 올릴 수도 있습니다: "
                            + link + "\n");
        }
        return new Message("Your email did not become an issue",
                "Your email could not be turned into an issue. If you belong to more than one company, add your "
                        + "company code to the address (for example " + local + "+CODE@" + domain + "). You can also "
                        + "file an issue in the portal: " + link + "\n");
    }

    /**
     * Notice B (S12-AC-18, screens.md): the reply was not added to the issue, which the customer sees: it is closed,
     * or only its reporter may reply. The key without brackets in the subject (S12-AC-29), the title, and the link.
     */
    static Message mailInNotAdded(String locale, Issue issue, boolean closed, String link) {
        String title = oneLine(issue.title());
        if (korean(locale)) {
            return new Message("문의 " + issue.key() + "에 댓글을 달지 못했습니다", closed
                    ? "문의 " + issue.key() + " \"" + title + "\"은 종료되어 메일 답장을 받지 않습니다. 이어서 말씀하실 내용은 "
                            + "포털에서 다시 열거나 새 문의로 올려 주세요: " + link + "\n"
                    :"문의 " + issue.key() + " \"" + title + "\"에는 문의를 올린 분만 답장할 수 있습니다. 포털에서 문의를 "
                            + "보거나 새 문의를 올려 주세요: " + link + "\n");
        }
        return new Message("Your reply was not added to issue " + issue.key(), closed
                ? "Issue " + issue.key() + " \"" + title + "\" is closed and takes no email replies. To go on, reopen it "
                        + "in the portal or file a new issue: " + link + "\n"
                : "Only the person who filed issue " + issue.key() + " \"" + title + "\" can reply to it by email. "
                        + "See the issue or file a new one in the portal: " + link + "\n");
    }

    /**
     * The form the emails of slice 4 about an issue share: {@code [key] headline: title}, then the
     * sentence, the key and the title, what to do, and the link. Nothing the issue or a comment says.
     */
    private static Message aboutIssue(String locale, Issue issue, String link, String headline, String sentence,
            String action) {
        String title = oneLine(issue.title());
        boolean korean = korean(locale);
        return new Message("[" + issue.key() + "] " + headline + ": " + title,
                sentence + "\n\n"
                        + (korean ? "문의: " : "Issue: ") + issue.key() + "\n"
                        + (korean ? "제목: " : "Title: ") + title + "\n\n"
                        + action + "\n\n"
                        + link + "\n");
    }

    /** Line breaks and other control characters as spaces, so a value cannot start a header (MAIL-2). */
    static String oneLine(String value) {
        return value.replaceAll("[\\p{Cntrl}\\u0085\\u2028\\u2029]+", " ");
    }

    /**
     * The test email an admin sends to check the SMTP settings (S4B-AC-28): the console's address and
     * the time it was sent, and nothing about anyone. The installation is named by its display name
     * (S9-AC-27).
     *
     * @param consoleLink the console's address, without a path
     * @param sentAt the time as text, with its time zone
     */
    static Message test(String locale, String displayName, String consoleLink, String sentAt) {
        String desk = oneLine(displayName);
        if (korean(locale)) {
            return new Message(desk + " 시험 메일",
                    desk + "의 메일 보내기 설정을 확인하는 시험 메일입니다.\n\n"
                            + "콘솔: " + consoleLink + "\n"
                            + "보낸 시각: " + sentAt + "\n");
        }
        return new Message(desk + " test email",
                "This is a test email to check the email settings of " + desk + ".\n\n"
                        + "Console: " + consoleLink + "\n"
                        + "Sent: " + sentAt + "\n");
    }

    static boolean korean(String locale) {
        return "ko".equalsIgnoreCase(locale);
    }
}
