package io.popdesk.helpdesk.web.console;

import io.popdesk.helpdesk.CompanyStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/** A customer company (PROJ-1). */
public record Company(
        @Schema(description = "2 to 10 capital letters and digits. Unique, never changes.") String code,
        String name,
        @Schema(description = "The company's legal name.") String legalName,
        CompanyStatus status,
        OffsetDateTime createdAt,
        @Schema(description = "Membership requests for this company that wait for an admin (S2-AC-48).")
                int pendingMembershipRequests) {}
