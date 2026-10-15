package io.popdesk.helpdesk.link;

import io.popdesk.helpdesk.LinkType;
import java.util.List;
import java.util.UUID;

/**
 * Whether a link between two issues can be made, and how it is stored (LINK-2, S6C-AC-03 to S6C-AC-06,
 * slice 6c design section 3): judged from the links both issues have now, those nobody may see included.
 * The caller reads them under the lock of both issue rows. No I/O; the trash (slice 7a) judges a restored
 * link with the same rules.
 */
public final class LinkRules {

    /** Links one issue may have, counting every one of them (S6C-AC-06). */
    public static final int LIMIT = 50;

    public static final String TO_SELF = "LINK_TO_SELF";
    public static final String EXISTS = "LINK_EXISTS";
    public static final String DUPLICATE_CHAIN = "LINK_DUPLICATE_CHAIN";
    public static final String LIMIT_EXCEEDED = "LINK_LIMIT_EXCEEDED";

    /**
     * A link as stored: the two issues in the order of the database's uuid type ({@link #compare}), its
     * type {@code DUPLICATE_OF} or {@code RELATED}, and for {@code DUPLICATE_OF} the issue that is the
     * duplicate, one of the two; null for {@code RELATED}.
     */
    public record Link(UUID lowIssueId, UUID highIssueId, LinkType type, UUID duplicateIssueId) {

        /** Whether the link joins these two issues, in either order. */
        public boolean joins(UUID one, UUID other) {
            return lowIssueId.equals(one) && highIssueId.equals(other)
                    || lowIssueId.equals(other) && highIssueId.equals(one);
        }

        /**
         * The other issue of the link.
         *
         * @param issueId one of the two issues
         * @return the one that is not {@code issueId}
         */
        public UUID otherThan(UUID issueId) {
            return lowIssueId.equals(issueId) ? highIssueId : lowIssueId;
        }

        /**
         * The type as seen from one of its issues (S6C-AC-13).
         *
         * @param issueId one of the two issues
         * @return {@code DUPLICATE_OF} on the duplicate, {@code DUPLICATED_BY} on its original,
         *     {@code RELATED} on either issue of a related link
         */
        public LinkType seenFrom(UUID issueId) {
            if (type == LinkType.RELATED) {
                return LinkType.RELATED;
            }
            return duplicateIssueId.equals(issueId) ? LinkType.DUPLICATE_OF : LinkType.DUPLICATED_BY;
        }

        /** Whether the issue is the duplicate of this link. */
        boolean hasDuplicate(UUID issueId) {
            return type == LinkType.DUPLICATE_OF && duplicateIssueId.equals(issueId);
        }

        /** Whether the issue is the original of this link: of a duplicate link, the issue that is not the duplicate. */
        boolean hasOriginal(UUID issueId) {
            return type == LinkType.DUPLICATE_OF && !duplicateIssueId.equals(issueId)
                    && (lowIssueId.equals(issueId) || highIssueId.equals(issueId));
        }
    }

    /** What {@link #decide} found: the link to store, or the error code that refuses it; one of the two is null. */
    public record Decision(Link link, String errorCode) {}

    private LinkRules() {}

    /**
     * Judges a new link from {@code issueId} to {@code otherIssueId}, in this order (S6C-AC-14): the same
     * issue ({@link #TO_SELF}), a link between the two already, of any type and direction
     * ({@link #EXISTS}), a duplicate chain ({@link #DUPLICATE_CHAIN}), then the limit of either issue
     * ({@link #LIMIT_EXCEEDED}). A duplicate link is refused as a chain when its original is a duplicate
     * already, or its duplicate is an original already or a duplicate already (S6C-AC-05); a related link
     * is not checked for chains.
     *
     * @param issueId the issue the link is made from, not null
     * @param otherIssueId the other issue, not null
     * @param type any of the three, as seen from {@code issueId}: {@code DUPLICATED_BY} makes the same link
     *     as {@code DUPLICATE_OF} from the other issue
     * @param linksOfIssue every link {@code issueId} has now, any number; empty for none
     * @param linksOfOther every link {@code otherIssueId} has now, any number; empty for none
     * @return the link to store, with the two issues in the order of {@link #compare}; or the error code
     */
    public static Decision decide(UUID issueId, UUID otherIssueId, LinkType type, List<Link> linksOfIssue,
            List<Link> linksOfOther) {
        if (issueId.equals(otherIssueId)) {
            return refuse(TO_SELF);
        }
        if (linksOfIssue.stream().anyMatch(l -> l.joins(issueId, otherIssueId))
                || linksOfOther.stream().anyMatch(l -> l.joins(issueId, otherIssueId))) {
            return refuse(EXISTS);
        }
        UUID duplicate = null;
        if (type != LinkType.RELATED) {
            boolean fromDuplicate = type == LinkType.DUPLICATE_OF;
            duplicate = fromDuplicate ? issueId : otherIssueId;
            UUID original = fromDuplicate ? otherIssueId : issueId;
            List<Link> ofDuplicate = fromDuplicate ? linksOfIssue : linksOfOther;
            List<Link> ofOriginal = fromDuplicate ? linksOfOther : linksOfIssue;
            UUID d = duplicate;
            if (ofOriginal.stream().anyMatch(l -> l.hasDuplicate(original))
                    || ofDuplicate.stream().anyMatch(l -> l.hasOriginal(d) || l.hasDuplicate(d))) {
                return refuse(DUPLICATE_CHAIN);
            }
        }
        if (linksOfIssue.size() >= LIMIT || linksOfOther.size() >= LIMIT) {
            return refuse(LIMIT_EXCEEDED);
        }
        boolean issueFirst = compare(issueId, otherIssueId) < 0;
        return new Decision(new Link(issueFirst ? issueId : otherIssueId, issueFirst ? otherIssueId : issueId,
                duplicate == null ? LinkType.RELATED : LinkType.DUPLICATE_OF, duplicate), null);
    }

    /**
     * Orders two uuids as the database's uuid type does: byte by byte, unsigned. {@link UUID#compareTo}
     * compares signed numbers and orders some pairs the other way.
     *
     * @return negative, zero, or positive as {@code a} is before, the same as, or after {@code b}
     */
    public static int compare(UUID a, UUID b) {
        int high = Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits());
        return high != 0 ? high : Long.compareUnsigned(a.getLeastSignificantBits(), b.getLeastSignificantBits());
    }

    private static Decision refuse(String code) {
        return new Decision(null, code);
    }
}
