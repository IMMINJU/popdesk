package io.popdesk.helpdesk.web.console;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/** An {@code ACTIVE} customer of a company, to pick as the reporter of an issue filed on behalf (S3-AC-40). */
public record CompanyReporter(
        long accountId,
        String name,
        String email,
        @Schema(description = "When the profile photo last changed; null without a photo (`getPhoto`).",
                nullable = true) OffsetDateTime photoUpdatedAt) {}
