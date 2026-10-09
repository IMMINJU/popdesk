package io.popdesk.helpdesk.system;

import io.popdesk.helpdesk.Viewer;
import io.popdesk.helpdesk.account.AccountRepository;
import io.popdesk.helpdesk.issue.IssueRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Whether an account may see an issue now, for system jobs that act for someone without a request: the
 * send loop checks it just before it sends an email (S0-AC-69, MAIL-4). The account's role and
 * memberships are read from the database at the time of the call, with the same scope as the portal and
 * the console: a reporter who left the company still sees their issue (permissions.md rule 2).
 */
@Component
public class IssueVisibility {

    private final AccountRepository accounts;
    private final IssueRepository issues;

    IssueVisibility(AccountRepository accounts, IssueRepository issues) {
        this.accounts = accounts;
        this.issues = issues;
    }

    /** Whether the account exists and may see the issue. Says nothing about the account's status. */
    public boolean canSee(long accountId, UUID issueId) {
        return accounts.findById(accountId)
                .map(account -> issues.visible(new Viewer(account.id(), account.role()), issueId))
                .orElse(false);
    }
}
