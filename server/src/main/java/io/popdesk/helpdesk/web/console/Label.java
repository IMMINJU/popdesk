package io.popdesk.helpdesk.web.console;

import io.swagger.v3.oas.annotations.media.Schema;

/** A label of a customer company (LBL-1). Customers never see labels (LBL-4). */
public record Label(
        long id,
        @Schema(description = "1 to 30 characters, unique in the company whatever the case.") String name,
        @Schema(description = "`#RRGGBB` in capital letters, for example `#808080`.") String color,
        @Schema(description = "Issues of the company that have this label now (S6A-AC-10).") long issueCount) {}
