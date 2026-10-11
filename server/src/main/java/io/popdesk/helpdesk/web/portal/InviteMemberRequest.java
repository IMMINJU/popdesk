package io.popdesk.helpdesk.web.portal;

import io.swagger.v3.oas.annotations.media.Schema;

/** No role and no company: the person is a customer of the company in the path (S2-AC-22). */
public record InviteMemberRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Up to 100 characters.") String email,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "1 to 50 characters.") String name) {}
