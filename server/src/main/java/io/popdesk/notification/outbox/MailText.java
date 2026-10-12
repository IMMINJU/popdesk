package io.popdesk.notification.outbox;

import io.popdesk.notification.IssueFacts.Issue;

/**
 * The subject and text of each email, in English and Korean (MAIL-1). Mail to customers names staff
 * only as the support team (D-49, S0-AC-70) and holds no comment text. Values in the subject get their
 * line breaks as spaces (S0-AC-71).
 */
final class MailText {

    /** One email's subject and plain text. */
    record Message(String subject, String text) {}

    private MailText() {}

    /** An invitation to the person's app (S0-AC-72). */
    static Message invitation(String locale, String name, String link) {
        if (korean(locale)) {
            return new Message("popdesk에 초대되었습니다",
                    name + "님, popdesk에 초대되었습니다.\n\n"
                            + "아래 링크에서 비밀번호를 정하면 로그인할 수 있습니다. 링크는 일주일 동안 쓸 수 있습니다.\n\n"
                            + link + "\n\n"
                            + "초대를 기다리지 않았다면 이 메일을 지워도 됩니다.\n");
        }
        return new Message("You are invited to popdesk",
                "Hello " + name + ",\n\n"
                        + "You are invited to popdesk. Set your password at the link below to sign in. The link works "
                        + "for one week.\n\n"
                        + link + "\n\n"
                        + "If you did not expect this invitation, you can delete this email.\n");
    }

    /** A link to set a new password (S1A-AC-06). It names no other account and says nothing of the old password. */
    static Message passwordReset(String locale, String name, String link) {
        if (korean(locale)) {
            return new Message("popdesk 비밀번호 재설정",
                    name + "님, popdesk 계정의 비밀번호 재설정 요청을 받았습니다.\n\n"
                            + "아래 링크에서 새 비밀번호를 정할 수 있습니다. 링크는 1시간 동안 쓸 수 있습니다.\n\n"
                            + link + "\n\n"
                            + "요청한 적이 없다면 이 메일을 지워도 됩니다.\n");
        }
        return new Message("Reset your popdesk password",
                "Hello " + name + ",\n\n"
                        + "A password reset was asked for your popdesk account. Set a new password at the link below. "
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

    static boolean korean(String locale) {
        return "ko".equalsIgnoreCase(locale);
    }
}
