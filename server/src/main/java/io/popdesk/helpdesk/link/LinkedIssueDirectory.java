package io.popdesk.helpdesk.link;

import io.popdesk.helpdesk.LinkedIssues;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** {@link LinkedIssues} on the table {@code issue_link}: every link, whoever may see it (S6C-AC-31). */
@Component
class LinkedIssueDirectory implements LinkedIssues {

    private final LinkRepository links;

    LinkedIssueDirectory(LinkRepository links) {
        this.links = links;
    }

    @Override
    public List<UUID> of(UUID issueId) {
        return links.linksOf(issueId).stream().map(l -> l.otherThan(issueId)).toList();
    }
}
