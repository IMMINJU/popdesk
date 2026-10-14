package io.popdesk.boundary;

import java.util.List;

/**
 * The boundary test list: every operation of both contract files, with the test methods
 * that check its boundary, or the reason it has none. {@link BoundaryListTest} holds this list to
 * the contract files and to the test classes.
 *
 * <p>A test is written {@code package.Class#method}, under {@code io.popdesk}. "Boundary" here is
 * what the caller may see or do: another customer company, a colleague's private issue, internal
 * comments, an agent's assigned companies, and actions for admins only.
 */
final class BoundaryList {

    /** One operation of the contract: method and path as the contract writes them. */
    record Entry(String method, String path, List<String> tests, String noBoundary) {

        String operation() {
            return method + " " + path;
        }
    }

    private BoundaryList() {}

    private static Entry tested(String method, String path, String... tests) {
        return new Entry(method, path, List.of(tests), null);
    }

    private static Entry none(String method, String path, String reason) {
        return new Entry(method, path, List.of(), reason);
    }

    private static final String ISSUE = "/api/portal/issues/{key}";
    private static final String STAFF_ISSUE = "/api/console/issues/{key}";
    private static final String BOUNDARY = "boundary.CompanyBoundaryTest#";
    private static final String STAFF = "boundary.StaffIdentityTest#";
    private static final String PHOTO = "boundary.PhotoBoundaryTest#";
    private static final String ADMIN_ONLY = "boundary.AgentCannotManageAccountsTest#";
    private static final String ISSUE_OF_B = "boundary.IssueBoundaryTest#";
    private static final String TALK = "boundary.TalkBoundaryTest#";
    private static final String TALK_STAFF = "boundary.TalkStaffIdentityTest#";
    private static final String INBOX = "inbox.InboxReadTest#";
    private static final String DELIVERY = "ops.DeliveryAccessTest#";
    private static final String IMAGE = "attachment.ImageReadTest#";
    private static final String LABEL_OF_B = "boundary.LabelBoundaryTest#";
    private static final String LABELS = "label.LabelManageTest#";

    static final List<Entry> ENTRIES = List.of(
            // Before sign-in, both hosts
            none("GET", "/api/public/csrf", "sets the CSRF cookie before sign-in; no customer data"),
            none("POST", "/api/public/sign-in", "named by the criterion as unrelated to the boundary: sign-in"),
            none("POST", "/api/public/sign-out", "named by the criterion as unrelated to the boundary: sign-out"),
            none("POST", "/api/public/invitations/accept", "named by the criterion as unrelated to the boundary: accepting an invitation"),

            // Portal
            none("GET", "/api/portal/me", "answers with the caller's own account; no input picks a target"),
            tested("GET", "/api/portal/companies",
                    BOUNDARY + "customerCompanyListHasNothingOfB",
                    STAFF + "companiesHoldNoStaff",
                    "project.CompanyListTest#customerSeesOwn"),
            tested("GET", "/api/portal/issues",
                    BOUNDARY + "customerIssueListHasNothingOfB",
                    BOUNDARY + "colleagueSeesNoPrivateIssue",
                    STAFF + "issuesHoldNoStaff",
                    "issue.IssueListTest#customerList"),
            tested("POST", "/api/portal/issues",
                    BOUNDARY + "customerOfACannotFileInB",
                    "issue.IssueCreateTest#customerOfOtherCompanyGets404",
                    "issue.IssueCreateTest#staffOnConsoleHostGet404"),
            tested("GET", ISSUE,
                    BOUNDARY + "customerOfASeesNoIssueOfB",
                    BOUNDARY + "colleagueSeesNoPrivateIssue",
                    STAFF + "issuesHoldNoStaff",
                    "issue.IssueListTest#customerDetail"),
            tested("GET", ISSUE + "/comments",
                    BOUNDARY + "customerOfASeesNoIssueOfB",
                    BOUNDARY + "colleagueSeesNoPrivateIssue",
                    BOUNDARY + "internalCommentNotListedForCustomers",
                    STAFF + "commentsHoldNoStaff",
                    "issue.CommentTest#internalCommentHiddenFromCustomer"),
            tested("POST", ISSUE + "/comments",
                    BOUNDARY + "customerOfACannotCommentOnB",
                    BOUNDARY + "colleagueCannotCommentOnPublicIssue",
                    "issue.CommentTest#colleagueGets403",
                    "issue.CommentTest#colleagueOnPrivateIssueGets404"),
            tested("GET", ISSUE + "/attachments",
                    BOUNDARY + "customerOfASeesNoIssueOfB",
                    BOUNDARY + "internalAttachmentNotListedForCustomers",
                    STAFF + "attachmentsHoldNoStaff",
                    "attachment.AttachmentNotFoundTest#internalAttachmentNotListed"),
            tested("POST", ISSUE + "/attachments",
                    BOUNDARY + "customerOfACannotAttachToB",
                    BOUNDARY + "colleagueCannotAttachToPublicIssue"),
            tested("GET", ISSUE + "/attachments/{id}",
                    BOUNDARY + "customerOfAGetsNoAttachmentOfB",
                    BOUNDARY + "colleagueGetsNoPrivateAttachment",
                    BOUNDARY + "internalAttachmentIsNotFoundForCustomers",
                    "attachment.AttachmentNotFoundTest#otherCompanysIssue"),
            tested("HEAD", ISSUE + "/attachments/{id}",
                    BOUNDARY + "customerOfAGetsNoAttachmentOfB",
                    BOUNDARY + "colleagueGetsNoPrivateAttachment",
                    BOUNDARY + "internalAttachmentIsNotFoundForCustomers"),
            tested("DELETE", ISSUE + "/attachments/{id}",
                    BOUNDARY + "customerOfAGetsNoAttachmentOfB",
                    BOUNDARY + "colleagueGetsNoPrivateAttachment",
                    BOUNDARY + "internalAttachmentIsNotFoundForCustomers"),

            // Console
            none("GET", "/api/console/me", "answers with the caller's own account; no input picks a target"),
            tested("GET", "/api/console/accounts",
                    "ops.CheckOrderTest#roleBeforeTargetAndInput"),
            tested("GET", "/api/console/companies",
                    BOUNDARY + "agentCompanyListHasNothingOfB",
                    "project.CompanyListTest#agentSeesAssigned",
                    "project.CompanyListTest#unassignedAgentSeesNone"),
            tested("POST", "/api/console/companies",
                    "project.CompanyPermissionTest#agentCannotCreateCompany"),
            tested("GET", "/api/console/companies/{code}",
                    BOUNDARY + "agentOfASeesNoIssueOfB",
                    "ops.CheckOrderTest#scopeBeforePermissionAndInput"),
            tested("GET", "/api/console/companies/{code}/agents",
                    "ops.CheckOrderTest#roleBeforeTargetAndInput"),
            tested("PUT", "/api/console/companies/{code}/agents/{accountId}",
                    "project.CompanyPermissionTest#agentCannotAssign",
                    "project.CompanyPermissionTest#agentGets403BeforeTargetCheck"),
            tested("GET", "/api/console/companies/{code}/customers",
                    "ops.CheckOrderTest#roleBeforeTargetAndInput"),
            tested("GET", "/api/console/invitations",
                    "project.PendingInvitationListTest#agentCannotList"),
            tested("POST", "/api/console/invitations",
                    "project.InvitationTest#agentCannotInvite"),
            tested("POST", "/api/console/invitations/{accountId}/link",
                    "project.InvitationLinkCopyTest#copyOnlyForPendingByAdmin"),
            tested("GET", "/api/console/issues",
                    BOUNDARY + "agentListHasNothingOfB",
                    "issue.IssueListTest#agentSeesAssignedCompanies",
                    "label.LabelListTest#labelOutOfScopeGivesAnEmptyList"),
            tested("GET", STAFF_ISSUE,
                    BOUNDARY + "agentOfASeesNoIssueOfB",
                    "issue.IssueListTest#agentSeesAssignedCompanies"),
            tested("GET", STAFF_ISSUE + "/comments",
                    BOUNDARY + "agentOfASeesNoIssueOfB"),
            tested("POST", STAFF_ISSUE + "/comments",
                    BOUNDARY + "agentOfACannotWriteOnB",
                    "issue.CommentTest#unassignedAgentGets404"),
            tested("GET", STAFF_ISSUE + "/attachments",
                    BOUNDARY + "agentOfASeesNoIssueOfB"),
            tested("POST", STAFF_ISSUE + "/attachments",
                    BOUNDARY + "agentOfACannotWriteOnB"),
            tested("GET", STAFF_ISSUE + "/attachments/{id}",
                    BOUNDARY + "agentOfAGetsNoAttachmentOfB",
                    "attachment.AttachmentNotFoundTest#unassignedAgent"),
            tested("HEAD", STAFF_ISSUE + "/attachments/{id}",
                    BOUNDARY + "agentOfAGetsNoAttachmentOfB",
                    "attachment.AttachmentNotFoundTest#unassignedAgent"),
            tested("DELETE", STAFF_ISSUE + "/attachments/{id}",
                    BOUNDARY + "agentOfAGetsNoAttachmentOfB",
                    "attachment.AttachmentNotFoundTest#unassignedAgent"),
            tested("GET", "/api/console/notifications",
                    "ops.OpsAlertTest#failedRowAlertsEveryAdmin",
                    TALK + "inboxesOfCompanyAHoldNothingOfCompanyB",
                    TALK + "agentTakenOffCompanyBKeepsOnlyTheItemsOfA",
                    INBOX + "itemOfAnIssueOutOfScopeIsHiddenUntilItIsBack",
                    INBOX + "anItemIsForOnePersonOnly"),
            tested("GET", "/api/console/notifications/unread-count",
                    TALK + "inboxesOfCompanyAHoldNothingOfCompanyB",
                    TALK + "agentTakenOffCompanyBKeepsOnlyTheItemsOfA",
                    INBOX + "itemOfAnIssueOutOfScopeIsHiddenUntilItIsBack"),
            tested("POST", "/api/console/notifications/{id}/read",
                    "ops.OpsAlertTest#cannotReadOthersItem",
                    INBOX + "readingAnotherPersonsItemIsNotFound"),
            none("GET", "/api/console/system/status",
                    "named by the criterion as unrelated to the boundary: system status (admins only is checked by ops.SystemStatusTest#agentForbidden)"),
            tested("GET", "/api/console/system/failed-deliveries",
                    "ops.SystemStatusTest#agentForbidden",
                    "ops.CheckOrderTest#roleBeforeTargetAndInput"),

            // Slice 1a (S1A-AC-77): the 24 operations it adds.
            // Before sign-in, both hosts
            none("POST", "/api/public/password-reset/request",
                    "named by the criterion as unrelated to the boundary: asking for a password reset link"),
            none("POST", "/api/public/password-reset/confirm",
                    "named by the criterion as unrelated to the boundary: setting a password with a reset token"),

            // Portal
            tested("PATCH", "/api/portal/me",
                    PHOTO + "ownAccountAnswersHoldNothingOfB"),
            none("PUT", "/api/portal/me/password",
                    "named by the criterion as unrelated to the boundary: changes the caller's own password; no input picks a target and the answer has no body"),
            tested("POST", "/api/portal/me/photo",
                    PHOTO + "ownAccountAnswersHoldNothingOfB"),
            none("DELETE", "/api/portal/me/photo",
                    "deletes the caller's own photo; no input picks a target and the answer has no body"),
            tested("GET", "/api/portal/photos/{accountId}",
                    PHOTO + "customerOfAGetsNoPhotoOfB",
                    "photo.PhotoAccessTest#otherPhotosAreLikeNoAccount",
                    "photo.PhotoAccessTest#portalAnswersHoldNoStaffPhoto",
                    "photo.PhotoResponseTest#rightIsCheckedBeforeNotModified"),

            // Console
            none("PATCH", "/api/console/me",
                    "named by the criterion as unrelated to the boundary: changes the caller's own name and language; no input picks a target"),
            none("PUT", "/api/console/me/password",
                    "named by the criterion as unrelated to the boundary: changes the caller's own password; no input picks a target and the answer has no body"),
            none("POST", "/api/console/me/photo",
                    "stores the caller's own photo and answers with the caller's own account; no input picks a target"),
            none("DELETE", "/api/console/me/photo",
                    "deletes the caller's own photo; no input picks a target and the answer has no body"),
            tested("GET", "/api/console/photos/{accountId}",
                    "photo.PhotoAccessTest#staffGetPhotosOfAccountsTheySee",
                    "photo.PhotoResponseTest#rightIsCheckedBeforeNotModified"),
            tested("PATCH", "/api/console/accounts/{accountId}",
                    ADMIN_ONLY + "agentCannotChangeAnAccount"),
            tested("PUT", "/api/console/accounts/{accountId}/role",
                    ADMIN_ONLY + "agentCannotChangeAnAccount"),
            tested("POST", "/api/console/accounts/{accountId}/suspend",
                    ADMIN_ONLY + "agentCannotChangeAnAccount"),
            tested("POST", "/api/console/accounts/{accountId}/reactivate",
                    ADMIN_ONLY + "agentCannotChangeAnAccount"),
            tested("POST", "/api/console/accounts/{accountId}/password-reset",
                    ADMIN_ONLY + "agentCannotChangeAnAccount"),
            tested("POST", "/api/console/invitations/{accountId}/resend",
                    ADMIN_ONLY + "agentCannotChangeAnInvitation"),
            tested("POST", "/api/console/invitations/{accountId}/cancel",
                    ADMIN_ONLY + "agentCannotChangeAnInvitation"),
            tested("PATCH", "/api/console/companies/{code}",
                    "project.CompanyUpdateTest#agentsCannotChangeACompany"),
            tested("PUT", "/api/console/companies/{code}/status",
                    "project.CompanyUpdateTest#agentsCannotChangeACompany"),
            tested("PUT", "/api/console/companies/{code}/customers/{accountId}",
                    "project.MembershipTest#agentsCannotChangeMembers",
                    "project.MembershipTest#addedCustomerSeesTheCompany"),
            tested("DELETE", "/api/console/companies/{code}/customers/{accountId}",
                    "project.MembershipTest#agentsCannotChangeMembers",
                    "project.MembershipTest#removedCustomerLosesTheCompany"),
            tested("DELETE", "/api/console/companies/{code}/agents/{accountId}",
                    "project.MembershipTest#agentsCannotChangeMembers",
                    "project.MembershipTest#unassignedAgentLosesTheCompany"),

            // Slice 2 (S2-AC-50): the 9 operations it adds. On the portal a manager of company A gets nothing
            // of company B; the console ones are for admins, who see every customer company.
            tested("GET", "/api/portal/companies/{code}/members",
                    "manager.MemberAccessTest#onlyManagersOfTheCompany"),
            tested("POST", "/api/portal/companies/{code}/members",
                    "manager.MemberAccessTest#onlyManagersOfTheCompany"),
            tested("DELETE", "/api/portal/companies/{code}/members/{id}",
                    "manager.MemberAccessTest#onlyManagersOfTheCompany",
                    "manager.MemberAccessTest#rowsOfTheCompanyInThePathOnly"),
            tested("POST", "/api/portal/companies/{code}/members/{id}/resend",
                    "manager.MemberAccessTest#onlyManagersOfTheCompany",
                    "manager.MemberAccessTest#rowsOfTheCompanyInThePathOnly"),
            none("PUT", "/api/console/companies/{code}/customers/{accountId}/manager",
                    "admins only (checked by manager.ManagerMarkTest#agentsCannotMark), and an admin sees every customer company; no customer company narrows what an admin may mark"),
            none("DELETE", "/api/console/companies/{code}/customers/{accountId}/manager",
                    "admins only (checked by manager.ManagerMarkTest#agentsCannotUnmark), and an admin sees every customer company; no customer company narrows what an admin may unmark"),
            none("GET", "/api/console/membership-requests",
                    "admins only (checked by ops.CheckOrderTest#roleBeforeTargetAndInput), and an admin sees every customer company; the list is not narrowed by a customer company"),
            none("POST", "/api/console/membership-requests/{id}/approve",
                    "admins only (checked by ops.CheckOrderTest#roleBeforeTargetAndInput), and an admin sees every customer company; no customer company narrows which request an admin may approve"),
            none("POST", "/api/console/membership-requests/{id}/reject",
                    "admins only (checked by ops.CheckOrderTest#roleBeforeTargetAndInput), and an admin sees every customer company; no customer company narrows which request an admin may reject"),

            // Slice 1b (S1B-AC-39): the 4 operations it adds. Only admins read the audit log, and an admin
            // sees every customer company, so no answer is narrowed by a customer company.
            none("GET", "/api/console/audit-log",
                    "admins only (checked by audit.AuditLogAccessTest#agentForbidden), and an admin sees every customer company; the log is not narrowed by a customer company"),
            none("GET", "/api/console/audit-log/export",
                    "admins only (checked by audit.AuditLogAccessTest#agentForbidden), and an admin sees every customer company; the file holds what the log holds"),
            none("HEAD", "/api/console/audit-log/export",
                    "admins only (checked by audit.AuditLogAccessTest#agentForbidden); answers with headers only, no audit entry and no customer data"),
            none("POST", "/api/console/audit-log/verify",
                    "admins only; checks the whole hash chain and answers with one result, which no customer company narrows"),

            // Slice 3 (S3-AC-88): the 14 operations it adds.
            // Portal
            tested("PATCH", ISSUE,
                    ISSUE_OF_B + "issueOfAnotherCompanyIsNotFound",
                    "issue.IssueEditTest#colleagueCannotEdit",
                    "issue.IssueEditTest#reporterTakenOutOfTheCompanyCannotEdit"),
            tested("POST", ISSUE + "/close",
                    ISSUE_OF_B + "issueOfAnotherCompanyIsNotFound",
                    "issue.IssueCloseReopenTest#colleagueCannotCloseOrReopen"),
            tested("POST", ISSUE + "/reopen",
                    ISSUE_OF_B + "issueOfAnotherCompanyIsNotFound",
                    "issue.IssueCloseReopenTest#colleagueCannotCloseOrReopen",
                    "issue.IssueCloseReopenTest#reporterTakenOutOfTheCompanyCannotReopen"),

            // Console
            tested("POST", "/api/console/issues",
                    "issue.OnBehalfFilingTest#unassignedAgentGetsNotFound",
                    "issue.OnBehalfFilingTest#accountsThatCannotBeTheReporterGetTheSame400",
                    "issue.IdempotencyKeyTest#keyBelongsToTheAccount"),
            tested("PATCH", STAFF_ISSUE,
                    ISSUE_OF_B + "issueOfAnotherCompanyIsNotFound"),
            tested("PUT", STAFF_ISSUE + "/status",
                    ISSUE_OF_B + "issueOfAnotherCompanyIsNotFound"),
            tested("POST", STAFF_ISSUE + "/reopen",
                    ISSUE_OF_B + "issueOfAnotherCompanyIsNotFound"),
            tested("PUT", STAFF_ISSUE + "/assignee",
                    ISSUE_OF_B + "issueOfAnotherCompanyIsNotFound",
                    "issue.IssueAssigneeTest#accountsThatCannotBeAssignedGetTheSame400"),
            tested("DELETE", STAFF_ISSUE + "/assignee",
                    ISSUE_OF_B + "issueOfAnotherCompanyIsNotFound"),
            tested("GET", STAFF_ISSUE + "/history",
                    ISSUE_OF_B + "issueOfAnotherCompanyIsNotFound",
                    "issue.IssueHistoryTest#portalHasNoHistory"),
            tested("GET", "/api/console/companies/{code}/assignees",
                    "issue.IssueAssigneeTest#unassignedAgentGetsNotFoundForCandidates"),
            tested("GET", "/api/console/companies/{code}/reporters",
                    "issue.CompanyReportersTest#unassignedAgentGetsNotFound",
                    "issue.CompanyReportersTest#onlyActiveCustomersOfTheCompany"),
            tested("GET", "/api/console/companies/{code}/agents/{accountId}/open-assignments",
                    "issue.ClearAssignmentsTest#agentsCannotCount",
                    "ops.CheckOrderTest#roleBeforeTargetAndInput"),
            tested("GET", "/api/console/accounts/{accountId}/open-assignments",
                    "issue.ClearAssignmentsTest#agentsCannotCount",
                    "ops.CheckOrderTest#roleBeforeTargetAndInput"),

            // Slice 4 (S4-AC-70): the 10 operations it adds. The inbox operations of the console that were
            // there before (list, unread count, read one) carry items about issues from this slice on; their
            // lines above name the tests of this slice too.
            // Portal
            tested("PATCH", ISSUE + "/comments/{id}",
                    "issue.CommentNotFoundTest#hiddenOrMissingCommentIsNotFound",
                    "issue.CommentEditTest#onlyTheAuthorEdits",
                    "issue.CommentEditTest#reporterWhoLeftTheCompanyMayNotEditOrDelete",
                    "ops.TalkCheckOrderTest#scopeBeforePermissionAndInput"),
            tested("DELETE", ISSUE + "/comments/{id}",
                    "issue.CommentNotFoundTest#hiddenOrMissingCommentIsNotFound",
                    "issue.CommentDeleteTest#othersMayNotDelete",
                    "issue.CommentEditTest#reporterWhoLeftTheCompanyMayNotEditOrDelete"),
            tested("GET", "/api/portal/notifications",
                    TALK + "inboxesOfCompanyAHoldNothingOfCompanyB",
                    INBOX + "anItemIsForOnePersonOnly",
                    INBOX + "customersGetNoStaffKinds",
                    TALK_STAFF + "answersAndMailsOfOneIssueHoldNoStaff"),
            tested("GET", "/api/portal/notifications/unread-count",
                    TALK + "inboxesOfCompanyAHoldNothingOfCompanyB",
                    INBOX + "anItemIsForOnePersonOnly"),
            tested("POST", "/api/portal/notifications/{id}/read",
                    INBOX + "readingAnotherPersonsItemIsNotFound"),
            tested("POST", "/api/portal/notifications/read-all",
                    INBOX + "readingAllReadsOwnItemsOnly"),

            // Console
            tested("PATCH", STAFF_ISSUE + "/comments/{id}",
                    "issue.CommentNotFoundTest#hiddenOrMissingCommentIsNotFound",
                    TALK + "authorTakenOffTheCompanyFindsNoCommentOfTheirs",
                    "issue.MentionTest#accountsThatCannotBeMentionedGetTheSame400",
                    "ops.TalkCheckOrderTest#scopeBeforePermissionAndInput"),
            tested("DELETE", STAFF_ISSUE + "/comments/{id}",
                    "issue.CommentNotFoundTest#hiddenOrMissingCommentIsNotFound",
                    TALK + "authorTakenOffTheCompanyFindsNoCommentOfTheirs",
                    "issue.CommentDeleteTest#othersMayNotDelete"),
            tested("GET", STAFF_ISSUE + "/mention-candidates",
                    "issue.MentionCandidatesTest#agentWhoCannotSeeTheIssueGetsNotFound",
                    "issue.MentionCandidatesTest#candidatesForPublicAndInternalComments",
                    "ops.TalkCheckOrderTest#scopeBeforePermissionAndInput"),
            tested("POST", "/api/console/notifications/read-all",
                    INBOX + "readingAllReadsOwnItemsOnly",
                    INBOX + "readingAllReadsHiddenItemsToo"),

            // Slice 4b (S4B-AC-37): the 2 operations it adds. Both are for admins only, on the console host
            // only; an admin sees every customer company, so no customer company narrows which failed row an
            // admin may send again, and the test mail goes to the caller's own address.
            tested("POST", "/api/console/system/failed-deliveries/{id}/resend",
                    DELIVERY + "agentIsForbiddenAndNothingHappens",
                    DELIVERY + "signedOutIsUnauthenticated",
                    DELIVERY + "withoutCsrfTokenIsForbidden",
                    DELIVERY + "portalHostHasNeitherEndpoint",
                    "ops.DeliveryCheckOrderTest#checksComeInOrder"),
            tested("POST", "/api/console/system/test-mail",
                    DELIVERY + "agentIsForbiddenAndNothingHappens",
                    DELIVERY + "signedOutIsUnauthenticated",
                    DELIVERY + "withoutCsrfTokenIsForbidden",
                    DELIVERY + "portalHostHasNeitherEndpoint",
                    "ops.DeliveryCheckOrderTest#checksComeInOrder"),

            // Slice 5 (S5-AC-11): the 6 operations it adds. A temporary image has no issue and no company yet,
            // so its boundary is the account that uploaded it: nobody else reads it, not a colleague of the same
            // company and not an admin. The tests named here check that; once an image is attached it is an
            // attachment of its issue, under the attachment lines above.
            none("POST", "/api/portal/images",
                    "stores a temporary image of the caller; no input picks a target and the answer is the new ID alone"),
            tested("GET", "/api/portal/images/{id}",
                    IMAGE + "othersGetTheUnknownIdAnswer",
                    IMAGE + "pathOfTheOtherApp",
                    IMAGE + "attachedImageLeavesTheTemporaryPath"),
            tested("HEAD", "/api/portal/images/{id}",
                    IMAGE + "othersGetTheUnknownIdAnswer",
                    IMAGE + "pathOfTheOtherApp",
                    IMAGE + "attachedImageLeavesTheTemporaryPath"),
            none("POST", "/api/console/images",
                    "stores a temporary image of the caller; no input picks a target and the answer is the new ID alone"),
            tested("GET", "/api/console/images/{id}",
                    IMAGE + "othersGetTheUnknownIdAnswer",
                    IMAGE + "pathOfTheOtherApp"),
            tested("HEAD", "/api/console/images/{id}",
                    IMAGE + "othersGetTheUnknownIdAnswer",
                    IMAGE + "pathOfTheOtherApp"),

            // Slice 6a (S6A-AC-92): the 8 operations it adds, all on the console. A label belongs to one customer
            // company: an agent of A reads, adds, and removes no label of B, a label of B is not added to an issue
            // of A, and a label of B is not edited or deleted under A's path. Creating, editing, and deleting are
            // for admins only. The issue writes (labels and due date) are refused for an issue the caller cannot see.
            tested("GET", "/api/console/companies/{code}/labels",
                    LABEL_OF_B + "agentOfAGetsNoLabelOfB",
                    LABEL_OF_B + "customerGetsTheConsoleAnswer"),
            tested("POST", "/api/console/companies/{code}/labels",
                    LABELS + "agentsCannotManageLabels",
                    LABEL_OF_B + "customerGetsTheConsoleAnswer"),
            tested("PATCH", "/api/console/companies/{code}/labels/{id}",
                    LABELS + "agentsCannotManageLabels",
                    LABEL_OF_B + "labelOfAnotherCompanyInThePathIsNotFound",
                    LABEL_OF_B + "customerGetsTheConsoleAnswer"),
            tested("DELETE", "/api/console/companies/{code}/labels/{id}",
                    LABELS + "agentsCannotManageLabels",
                    LABEL_OF_B + "labelOfAnotherCompanyInThePathIsNotFound",
                    LABEL_OF_B + "customerGetsTheConsoleAnswer"),
            tested("PUT", STAFF_ISSUE + "/labels/{id}",
                    LABEL_OF_B + "agentOfACannotLabelAnIssueOfB",
                    LABEL_OF_B + "labelOfBIsNotAddedToAnIssueOfA",
                    LABEL_OF_B + "customerGetsTheConsoleAnswer"),
            tested("DELETE", STAFF_ISSUE + "/labels/{id}",
                    LABEL_OF_B + "agentOfACannotLabelAnIssueOfB",
                    LABEL_OF_B + "customerGetsTheConsoleAnswer"),
            tested("PUT", STAFF_ISSUE + "/due-date",
                    LABEL_OF_B + "agentOfACannotLabelAnIssueOfB",
                    LABEL_OF_B + "customerGetsTheConsoleAnswer"),
            tested("DELETE", STAFF_ISSUE + "/due-date",
                    LABEL_OF_B + "agentOfACannotLabelAnIssueOfB",
                    LABEL_OF_B + "customerGetsTheConsoleAnswer"));
}
