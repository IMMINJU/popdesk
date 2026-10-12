package io.popdesk.helpdesk.web.console;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

/** A public or internal comment. A deleted comment stays in the list as a trace (COM-7). */
public record Comment(
        long id,
        Person author,
        @Schema(description = "Internal comments do not exist for customers (CMT-2).") boolean internal,
        @Schema(description = "Markdown, as written (COM-6). An empty string once the comment is deleted (COM-7).")
                String content,
        OffsetDateTime createdAt,
        @Schema(description = "When the author last changed the content; null when it never changed (S4-AC-07).",
                nullable = true) OffsetDateTime editedAt,
        boolean deleted,
        @Schema(nullable = true) OffsetDateTime deletedAt,
        @Schema(description = "The author, or an admin (CMT-3).", nullable = true) Person deletedBy,
        @Schema(description = "The accounts the comment mentions, as they were chosen: someone who can no longer "
                + "see the issue stays in the list (S4-AC-19, S4-AC-26). In name order, then by account ID.")
                List<Person> mentions) {}
