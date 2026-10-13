package io.popdesk.helpdesk.web.console;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** An image uploaded for a content that is not saved yet. */
public record TemporaryImage(
        @Schema(description = "The ID to write as `attachment:{id}` and to send in `imageIds`. Hard to guess, "
                + "but not a permission check (ATT-7). It stays the attachment's ID once the image is attached.")
                UUID id) {}
