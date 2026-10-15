package io.popdesk.helpdesk.web.console;

import io.swagger.v3.oas.annotations.media.Schema;

/** A link named by a {@code LINK} history entry, read when the history is (S6C-AC-21). */
public record IssueHistoryLink(
        @Schema(description = "Seen from the issue whose history this is (S6C-AC-13).") IssueLinkType type,
        @Schema(description = "The other issue, by its current key and title.") IssueRef issue) {}
