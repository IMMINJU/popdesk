package io.popdesk.helpdesk.web.console;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** The links of one issue the caller can see, all at once: an issue has at most 50 links (S6C-AC-06). */
public record IssueLinks(
        @Schema(description = "Only links whose other issue the caller can see, in the order they were made "
                + "(S6C-AC-12). A link the caller cannot see is left out and not counted anywhere; it is back "
                + "when the caller can see the other issue again.") List<IssueLink> items) {}
