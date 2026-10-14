package io.popdesk.helpdesk.web.console;

import io.swagger.v3.oas.annotations.media.Schema;

/** Read as text so that a value that is not a date is the field error of the contract, not a JSON error. */
public record SetDueDateRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", format = "date",
                description = "`YYYY-MM-DD`, today or later in the installation time zone (`POPDESK_TIMEZONE`, "
                        + "S6A-AC-30, S6A-AC-34). A time, an offset, or a day that does not exist (`2026-02-30`) "
                        + "is not taken (S6A-AC-31).") String dueDate) {}
