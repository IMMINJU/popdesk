package io.popdesk.helpdesk.issue;

import io.popdesk.helpdesk.Viewer;

/**
 * Which issues a viewer may see, as SQL on the issue alias {@code i} or another (S0-AC-47, permissions.md
 * "The customer-company boundary"): admins every issue; agents every issue of the companies assigned
 * to them; customers every issue they reported, and the public issues of the companies they belong to
 * now. A customer who left a company keeps seeing what they reported there, and only that.
 * Repositories that read under an issue (comments, attachments) put the same condition in their query.
 */
public final class IssueScope {

    /** The condition, with the parameters of {@link #params}. */
    public static final String SQL = on("i");

    private IssueScope() {}

    /**
     * The condition on the issue alias {@code alias}, for a query that reads a second issue beside
     * {@code i} (slice 6c design section 5), with the parameters of {@link #params}.
     */
    public static String on(String alias) {
        return "(? = 'ADMIN' "
                + "or (? = 'AGENT' and exists (select 1 from company_agent ca "
                + "where ca.company_id = " + alias + ".company_id and ca.account_id = ?)) "
                + "or (? = 'CUSTOMER' and (" + alias + ".reporter_id = ? or (not " + alias + ".is_private and "
                + "exists (select 1 from company_member cm where cm.company_id = " + alias + ".company_id "
                + "and cm.account_id = ?)))))";
    }

    /** The parameters of {@link #SQL}, in order. */
    public static Object[] params(Viewer viewer) {
        String role = viewer.role().name();
        long id = viewer.accountId();
        return new Object[] {role, role, id, role, id, id};
    }

    /** {@code first} followed by {@code more}, for the parameters of a query. */
    public static Object[] concat(Object[] first, Object... more) {
        Object[] out = new Object[first.length + more.length];
        System.arraycopy(first, 0, out, 0, first.length);
        System.arraycopy(more, 0, out, first.length, more.length);
        return out;
    }
}
