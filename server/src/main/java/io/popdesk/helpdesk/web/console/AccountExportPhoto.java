package io.popdesk.helpdesk.web.console;

import io.swagger.v3.oas.annotations.media.Schema;

/** The profile photo in an {@link AccountExport}, as the PNG popdesk keeps (ACCT-8). */
public record AccountExportPhoto(
        @Schema(description = "Always `image/png`.") String contentType,
        @Schema(description = "The PNG file, Base64 (RFC 4648, with padding, no line breaks).") String base64) {}
