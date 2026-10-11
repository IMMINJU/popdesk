package io.popdesk.helpdesk.web.portal;

import io.popdesk.helpdesk.CompanyStatus;
import io.swagger.v3.oas.annotations.media.Schema;

/** A customer company the caller belongs to. */
public record Company(
        @Schema(description = "Unique, never changes. Issue keys start with it.") String code,
        String name,
        CompanyStatus status,
        @Schema(description = "The caller is a manager of this company (MGR-1).") boolean manager) {}
