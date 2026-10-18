package io.popdesk.helpdesk.account;

/**
 * What an anonymized account is called from then on (PII-4, acceptance.md 2절 "말의 뜻"): the anonymous
 * name in the installation's language when it is anonymized, stored as text so it does not change with
 * the language later (acceptance.md 5절 10), and the email {@code deleted-{id}@invalid}, which no mail
 * reaches and no other account has.
 */
public record AnonymousValues(String name, String email) {

    /** The anonymous name in English. */
    static final String NAME_EN = "Deleted user";

    /** The anonymous name in Korean. */
    static final String NAME_KO = "삭제된 사용자";

    /** The values of the account in the installation's language, {@code en} or {@code ko}. */
    public static AnonymousValues of(long accountId, String language) {
        return new AnonymousValues("ko".equals(language) ? NAME_KO : NAME_EN, "deleted-" + accountId + "@invalid");
    }
}
