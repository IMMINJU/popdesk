package io.popdesk.helpdesk.link;

import io.popdesk.helpdesk.link.LinkRules.Link;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Whether an issue can come back from the trash with its links (TRASH-2, S7A-AC-24): the links it has
 * must keep {@link LinkRules} (LINK-2) as if each were made now, those nobody may see included. While the
 * issue is in the trash its links still count for every new link, so this finds nothing unless a later
 * way of changing links skips that count (slice 7a acceptance section 5, 9). No I/O; the caller reads the
 * links under the lock of the issue and of every issue linked to it.
 */
public final class RestoreLinkCheck {

    private RestoreLinkCheck() {}

    /**
     * The links of the issue that break the link rules, in the order of {@code links}. A link is at fault
     * when:
     *
     * <ul>
     *   <li>the issue has more than {@link LinkRules#LIMIT} links: every link after the first
     *       {@link LinkRules#LIMIT};
     *   <li>its other issue has more than {@link LinkRules#LIMIT} links;
     *   <li>it is a duplicate link whose original is a duplicate itself, or whose duplicate has another
     *       original or is an original itself (a duplicate chain, S6C-AC-05), seen from either issue.
     * </ul>
     *
     * @param issueId the issue to restore, not null
     * @param links every link the issue has, in the order they were made, any number; empty for none.
     *     Each joins {@code issueId} and another issue
     * @param linksOfOthers for each other issue of {@code links}, every link it has, the one to
     *     {@code issueId} included; an issue missing from the map counts as having that one link only
     * @return the links at fault, each once; empty when the issue can be restored
     */
    public static List<Link> atFault(UUID issueId, List<Link> links, Map<UUID, List<Link>> linksOfOthers) {
        List<Link> out = new ArrayList<>();
        for (int i = 0; i < links.size(); i++) {
            Link link = links.get(i);
            UUID other = link.otherThan(issueId);
            List<Link> ofOther = linksOfOthers.getOrDefault(other, List.of(link));
            if (i >= LinkRules.LIMIT || ofOther.size() > LinkRules.LIMIT || chain(link, issueId, links, ofOther)) {
                out.add(link);
            }
        }
        return out;
    }

    /**
     * Whether a duplicate link makes a chain: its original is the duplicate of another link, or its
     * duplicate is the duplicate of another link or the original of one. A related link makes none.
     */
    private static boolean chain(Link link, UUID issueId, List<Link> links, List<Link> ofOther) {
        UUID duplicate = link.duplicateIssueId();
        if (duplicate == null) {
            return false;
        }
        UUID original = link.otherThan(duplicate);
        List<Link> ofDuplicate = duplicate.equals(issueId) ? links : ofOther;
        List<Link> ofOriginal = original.equals(issueId) ? links : ofOther;
        return ofOriginal.stream().anyMatch(l -> l.hasDuplicate(original))
                || ofDuplicate.stream().anyMatch(l -> !l.equals(link) && (l.hasOriginal(duplicate)
                        || l.hasDuplicate(duplicate)));
    }
}
