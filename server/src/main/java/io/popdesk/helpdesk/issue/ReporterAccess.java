package io.popdesk.helpdesk.issue;

import io.popdesk.helpdesk.Viewer;
import io.popdesk.helpdesk.company.CompanyRepository;
import io.popdesk.helpdesk.issue.IssueRepository.IssueRow;
import org.springframework.stereotype.Component;

/**
 * Which customer may write under an issue (PROJ-4, COM-2): the one who reported it, while they still
 * belong to its company. A colleague who can see the issue, and the reporter after leaving the
 * company, may read it and nothing more. Comments, attachments, edits, and reopening ask this; closing
 * asks only for the reporter (S3-AC-54).
 */
@Component
public class ReporterAccess {

    private final CompanyRepository companies;

    ReporterAccess(CompanyRepository companies) {
        this.companies = companies;
    }

    /** Whether {@code customer} reported the issue and still belongs to its company. */
    public boolean writes(Viewer customer, IssueRow issue) {
        return issue.reporterId() == customer.accountId()
                && companies.findByCode(customer, issue.companyCode()).isPresent();
    }
}
