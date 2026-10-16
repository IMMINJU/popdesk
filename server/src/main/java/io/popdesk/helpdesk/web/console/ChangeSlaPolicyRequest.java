package io.popdesk.helpdesk.web.console;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

/** The whole policy as it should be, with the version it was read at (S8A-AC-02). */
public record ChangeSlaPolicyRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "The `version` of the policy as the caller read it (S8A-AC-03).") Integer version,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Four items, one for each priority, in "
                + "any order. A goal left null is no goal.") List<SlaTarget> targets,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Seven items, one for each weekday, in "
                + "any order. `opens` and `closes` both null for a day without business hours.") List<SlaHours> hours,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "`YYYY-MM-DD`, in any order, at most "
                + "500, no day twice; popdesk keeps them earliest first.") List<LocalDate> holidays) {}
