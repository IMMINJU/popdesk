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

    /** Line breaks and other control characters as spaces, so a value cannot start a header (MAIL-2). */
    static String oneLine(String value) {
        return value.replaceAll("[\\p{Cntrl}\\u0085\\u2028\\u2029]+", " ");
    }

    static boolean korean(String locale) {
        return "ko".equalsIgnoreCase(locale);
    }
}
