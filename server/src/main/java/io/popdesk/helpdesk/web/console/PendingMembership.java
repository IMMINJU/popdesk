package io.popdesk.helpdesk.web.console;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/**
 * A membership request still open: a customer manager invited an email that already has an account,
 * and an admin decides (MGR-6). Not named {@code MembershipRequest}: the contract reads a type whose
 * name ends in {@code Request} as a request body ({@code OpenApiConfig}).
 */
public record PendingMembership(
        long id,
        CompanyRef company,
        MembershipRequester requestedBy,
        @Schema(description = "The name the manager typed. The account's own name is in `account`.") String name,
        @Schema(description = "The email the manager typed.") String email,
        MembershipRequestAccount account,
        OffsetDateTime requestedAt) {}
