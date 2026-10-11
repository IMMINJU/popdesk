package io.popdesk.helpdesk.web.console;

import io.popdesk.helpdesk.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/** Who made a change of an issue's history, by the account's current name (HIST-1). */
public record IssueHistoryActor(
        @Schema(description = "0 for popdesk itself.") long accountId,
        @Schema(description = "Null for popdesk itself.", nullable = true) String name,
        @Schema(description = "Null for popdesk itself.", nullable = true) Role role,
        @Schema(description = "When the profile photo last changed; null without a photo (`getPhoto`) and for "
                + "popdesk itself.", nullable = true) OffsetDateTime photoUpdatedAt) {}
