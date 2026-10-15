package io.popdesk.helpdesk.web.console;

import io.popdesk.helpdesk.IssueStatus;
import io.swagger.v3.oas.annotations.media.Schema;

/** The other issue of a link, as it is now (S6C-AC-01). It may belong to another company. */
public record LinkedIssue(
        @Schema(description = "`{company code}-{number}`, for example `ACME-7`.") String key,
        String title,
        IssueStatus status,
        String companyCode,
        String companyName) {}
