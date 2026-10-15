package io.popdesk.helpdesk;

import java.util.List;
import java.util.UUID;

/**
 * The issues linked to an issue (LINK-6, S6C-AC-31). The link package implements it, so that the issue
 * package, which tells people of a status change, does not depend on links (ADR D-57).
 */
public interface LinkedIssues {

    /**
     * Every issue linked to this one, whoever may see it, in the order the links were made; empty without
     * links. Reads the links only; locks nothing.
     */
    List<UUID> of(UUID issueId);
}
