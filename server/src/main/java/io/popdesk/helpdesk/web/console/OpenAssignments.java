package io.popdesk.helpdesk.web.console;

import io.swagger.v3.oas.annotations.media.Schema;

/** What an account is still assigned to (ACCT-7). */
public record OpenAssignments(
        @Schema(description = "Issues that are not `CLOSED` and have this account as assignee.") long issues,
        @Schema(description = "Tasks that are `OPEN`, not deleted, on issues that are not `CLOSED`, and have this "
                + "account as assignee; for an agent taken off a company, only tasks of that company's issues "
                + "(S6B-AC-75).") long tasks) {}
