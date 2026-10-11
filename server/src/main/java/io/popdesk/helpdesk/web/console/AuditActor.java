package io.popdesk.helpdesk.web.console;

import io.swagger.v3.oas.annotations.media.Schema;

/** The person of an audit entry, as stored when the entry was written (AUDIT-2). */
public record AuditActor(
        @Schema(description = "0 for popdesk itself (the first admin, commands in the container).") long accountId,
        @Schema(description = "Null for popdesk itself.", nullable = true) String name,
        @Schema(description = "Null for popdesk itself.", nullable = true) String email) {}
