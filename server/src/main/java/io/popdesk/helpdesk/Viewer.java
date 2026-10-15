package io.popdesk.helpdesk;

/**
 * Who is reading, and so what they may see (PROJ-5, docs/use/permissions.md): admins see every
 * company, agents the companies they are assigned to, customers the companies they belong to. Every
 * read method of a customer-data repository takes one (S0-AC-84); its query applies the scope, read
 * from the database on every request (S0-AC-26). Reads without a scope belong to
 * {@code io.popdesk.helpdesk.system} only.
 *
 * @param accountId the signed-in account
 * @param role its role, read from the database on this request
 */
public record Viewer(long accountId, Role role) {

    /** The account ID that stands for popdesk itself; no account has it. */
    public static final long SYSTEM_ACCOUNT_ID = 0;

    /**
     * Popdesk itself, for what a cleanup job does without a request (slice 7a design section 0, 6): account
     * 0 with the scope of an admin. As an actor it is staff, and it is no account anyone is told about.
     */
    public static Viewer system() {
        return new Viewer(SYSTEM_ACCOUNT_ID, Role.ADMIN);
    }

    public boolean admin() {
        return role == Role.ADMIN;
    }

    public boolean agent() {
        return role == Role.AGENT;
    }

    public boolean customer() {
        return role == Role.CUSTOMER;
    }

    /** Staff: agents and admins, who work in the console. */
    public boolean staff() {
        return role != Role.CUSTOMER;
    }
}
