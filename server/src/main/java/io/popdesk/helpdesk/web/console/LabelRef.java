package io.popdesk.helpdesk.web.console;

import io.swagger.v3.oas.annotations.media.Schema;

/** A label named inside an issue, with its current name and color (S6A-AC-05). */
public record LabelRef(
        long id,
        String name,
        @Schema(description = "`#RRGGBB` in capital letters.") String color) {}
