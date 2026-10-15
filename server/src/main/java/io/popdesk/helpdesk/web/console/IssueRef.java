package io.popdesk.helpdesk.web.console;

import io.swagger.v3.oas.annotations.media.Schema;

/** An issue named inside another item. */
public record IssueRef(
        @Schema(description = "`{company code}-{number}`, for example `ACME-7`.") String key,
        @Schema(description = "The title as it is now, not as it was when the item was made.") String title) {}
