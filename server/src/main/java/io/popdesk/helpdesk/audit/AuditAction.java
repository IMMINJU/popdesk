package io.popdesk.helpdesk.audit;

/**
 * The kinds of action the audit log holds (AUDIT-1), as the contract names them. A stored row keeps
 * the name as text: a name is never renamed or reused, because it is part of the row's hash.
 */
public enum AuditAction {
    SIGN_IN_SUCCEEDED,
    SIGN_IN_FAILED,
    ACCOUNT_LOCKED,
    FIRST_ADMIN_CREATED,
    ACCOUNT_NAME_CHANGED,
    ACCOUNT_ROLE_CHANGED,
    ACCOUNT_SUSPENDED,
    ACCOUNT_REACTIVATED,
    ACCOUNT_PASSWORD_RESET,
    PASSWORD_CHANGED,
    PASSWORD_RESET_COMPLETED,
    INVITATION_CREATED,
    INVITATION_RESENT,
    INVITATION_CANCELLED,
    INVITATION_LINK_CREATED,
    COMPANY_CREATED,
    COMPANY_UPDATED,
    COMPANY_STATUS_CHANGED,
    CUSTOMER_ADDED,
    CUSTOMER_REMOVED,
    AGENT_ASSIGNED,
    AGENT_UNASSIGNED,
    ADMIN_PASSWORD_RESET,
    BACKUP_CREATED,
    DATABASE_RESTORED,
    AUDIT_EXPORTED
}
