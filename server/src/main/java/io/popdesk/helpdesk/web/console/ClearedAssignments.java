package io.popdesk.helpdesk.web.console;

import io.swagger.v3.oas.annotations.media.Schema;

/** What a request with {@code clearAssignments} took the account off (ACCT-7). */
public record ClearedAssignments(
        @Schema(description = "Issues whose assignee was cleared. 0 without `clearAssignments`.") long issues) {}
