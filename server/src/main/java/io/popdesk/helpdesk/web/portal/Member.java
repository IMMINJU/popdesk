package io.popdesk.helpdesk.web.portal;

import io.popdesk.helpdesk.AccountStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * A row of a company's member list, as its manager sees it (MGR-2): a member, or an invitation still
 * waiting. No account ID, photo, other companies, or times (S2-AC-10).
 */
public record Member(
        @Schema(description = "Names the row in this company's list. Random; not an account ID.") UUID id,
        String name,
        String email,
        @Schema(description = "`INVITED`: has not joined yet.") AccountStatus status,
        @Schema(description = "A manager of this company.") boolean manager) {}
