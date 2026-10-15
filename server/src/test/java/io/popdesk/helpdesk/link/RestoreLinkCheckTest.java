package io.popdesk.helpdesk.link;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.helpdesk.LinkType;
import io.popdesk.helpdesk.link.LinkRules.Link;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Which links of an issue keep it from coming back from the trash (TRASH-2, S7A-AC-24, LINK-2; slice 7a
 * acceptance section 5, 9 and design section 7): the limit of 50 on either issue and duplicate chains seen
 * from either issue. The only test of S7A-AC-24 (409 RESTORE_LINK_CONFLICT). Written from the Javadoc of
 * {@link RestoreLinkCheck} and {@link LinkRules.Link} alone.
 */
class RestoreLinkCheckTest {

    /** The issue to restore, in the middle of the others in the database's uuid order. */
    private static final UUID I = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID Z = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID O = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID D = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID P = UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final UUID W = UUID.fromString("88888888-8888-8888-8888-888888888888");
    private static final UUID Q = UUID.fromString("99999999-9999-9999-9999-999999999999");
    private static final UUID R = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    /**
     * The database's uuid order, worked out apart from the class under test: the lowercase text of a
     * uuid is its bytes in hex, so comparing the text compares the bytes unsigned.
     */
    private static boolean before(UUID a, UUID b) {
        return a.toString().compareTo(b.toString()) < 0;
    }

    private static Link stored(UUID one, UUID other, LinkType type, UUID duplicate) {
        return before(one, other)
                ? new Link(one, other, type, duplicate)
                : new Link(other, one, type, duplicate);
    }

    /** {@code duplicate} is a duplicate of {@code original}. */
    private static Link duplicateOf(UUID duplicate, UUID original) {
        return stored(duplicate, original, LinkType.DUPLICATE_OF, duplicate);
    }

    private static Link related(UUID one, UUID other) {
        return stored(one, other, LinkType.RELATED, null);
    }

    /**
     * {@code count} related links of {@code issue} to issues met nowhere else, numbered from {@code from};
     * the strangers of one issue differ from those of another.
     */
    private static List<Link> filler(UUID issue, int from, int count) {
        long high = 0xF000_0000_0000_0000L | (issue.getMostSignificantBits() >>> 8);
        List<Link> links = new ArrayList<>();
        for (int i = from; i < from + count; i++) {
            links.add(related(issue, new UUID(high, i)));
        }
        return links;
    }

    private static List<Link> filler(UUID issue, int count) {
        return filler(issue, 0, count);
    }

    @SafeVarargs
    private static List<Link> concat(List<Link>... parts) {
        List<Link> all = new ArrayList<>();
        for (List<Link> part : parts) {
            all.addAll(part);
        }
        return all;
    }

    // ---- nothing at fault ----

    @Test
    @DisplayName("S7A-AC-24 an issue with no links can be restored")
    void noLinks() {
        assertThat(RestoreLinkCheck.atFault(I, List.of(), Map.of())).isEmpty();
    }

    @Test
    @DisplayName("S7A-AC-24 links that keep LINK-2, of both types and directions, find nothing")
    void keepsTheRules() {
        List<Link> links = List.of(duplicateOf(I, O), related(I, P), related(I, Q));
        Map<UUID, List<Link>> others = Map.of(
                O, List.of(duplicateOf(I, O), related(O, Z)),
                P, List.of(related(I, P), duplicateOf(P, Z)),
                Q, List.of(related(I, Q), duplicateOf(W, Q)));
        assertThat(RestoreLinkCheck.atFault(I, links, others)).isEmpty();
    }

    // ---- the limit of the issue ----

    @Test
    @DisplayName("S7A-AC-24 exactly 50 links are within the limit")
    void fiftyLinks() {
        assertThat(LinkRules.LIMIT).isEqualTo(50);
        assertThat(RestoreLinkCheck.atFault(I, filler(I, 50), Map.of())).isEmpty();
    }

    @Test
    @DisplayName("S7A-AC-24 with 51 links the 51st is at fault, and only it")
    void fiftyOneLinks() {
        List<Link> links = filler(I, 51);
        assertThat(RestoreLinkCheck.atFault(I, links, Map.of())).containsExactly(links.get(50));
    }

    @Test
    @DisplayName("S7A-AC-24 with 52 links the 51st and the 52nd are at fault, in that order")
    void fiftyTwoLinks() {
        List<Link> links = filler(I, 52);
        assertThat(RestoreLinkCheck.atFault(I, links, Map.of())).containsExactly(links.get(50), links.get(51));
    }

    @Test
    @DisplayName("S7A-AC-24 the links after the first 50 are at fault by place, whatever their type")
    void overLimitByPlaceNotType() {
        List<Link> links = concat(List.of(duplicateOf(D, I)), filler(I, 49),
                List.of(duplicateOf(Q, I), related(I, R)));
        assertThat(RestoreLinkCheck.atFault(I, links, Map.of())).containsExactly(duplicateOf(Q, I), related(I, R));
    }

    // ---- the limit of the other issue ----

    @Test
    @DisplayName("S7A-AC-24 another issue with exactly 50 links, the one to the issue included, is within the limit")
    void otherAtFifty() {
        Link link = related(I, O);
        List<Link> ofO = concat(filler(O, 49), List.of(link));
        assertThat(ofO).hasSize(50);
        assertThat(RestoreLinkCheck.atFault(I, List.of(link), Map.of(O, ofO))).isEmpty();
    }

    @Test
    @DisplayName("S7A-AC-24 another issue with 51 links puts the link to it at fault, and no other link")
    void otherAtFiftyOne() {
        Link toO = related(I, O);
        Link toP = related(I, P);
        List<Link> ofO = concat(filler(O, 50), List.of(toO));
        assertThat(ofO).hasSize(51);
        Map<UUID, List<Link>> others = Map.of(O, ofO, P, concat(filler(P, 49), List.of(toP)));
        assertThat(RestoreLinkCheck.atFault(I, List.of(toP, toO), others)).containsExactly(toO);
    }

    @Test
    @DisplayName("S7A-AC-24 the limit of another issue counts a duplicate link to the issue like any other")
    void otherAtFiftyOneDuplicate() {
        Link link = duplicateOf(I, O);
        List<Link> ofO = concat(List.of(link), filler(O, 50));
        assertThat(RestoreLinkCheck.atFault(I, List.of(link), Map.of(O, ofO))).containsExactly(link);
    }

    // ---- an issue missing from the map ----

    @Test
    @DisplayName("S7A-AC-24 an issue missing from the map has only its link to the issue: no limit, no chain")
    void missingFromMap() {
        assertThat(RestoreLinkCheck.atFault(I, List.of(duplicateOf(I, O), related(I, P)), Map.of())).isEmpty();
        assertThat(RestoreLinkCheck.atFault(I, List.of(duplicateOf(D, I), related(I, P)), Map.of())).isEmpty();
    }

    @Test
    @DisplayName("S7A-AC-24 an issue missing from the map makes no chain while one in the map does")
    void missingAndPresent() {
        Link fromW = duplicateOf(W, I);
        Link fromD = duplicateOf(D, I);
        Map<UUID, List<Link>> others = Map.of(D, List.of(fromD, duplicateOf(D, Z)));
        assertThat(RestoreLinkCheck.atFault(I, List.of(fromW, fromD), others)).containsExactly(fromD);
    }

    // ---- duplicate chains ----

    /** Chains seen from the other issue: what it has besides its link to I. */
    static Stream<Arguments> chainsOfOther() {
        return Stream.of(
                Arguments.of("I is the duplicate, its original O is a duplicate itself",
                        duplicateOf(I, O), O, duplicateOf(O, Z)),
                Arguments.of("I is the duplicate, its original O is a duplicate of an issue after it",
                        duplicateOf(I, O), O, duplicateOf(O, R)),
                Arguments.of("I is the original, its duplicate D has another original",
                        duplicateOf(D, I), D, duplicateOf(D, Z)),
                Arguments.of("I is the original, its duplicate D has another original after it",
                        duplicateOf(D, I), D, duplicateOf(D, R)),
                Arguments.of("I is the original, its duplicate D is an original itself",
                        duplicateOf(D, I), D, duplicateOf(W, D)),
                Arguments.of("I is the original, its duplicate D is an original of an issue before it",
                        duplicateOf(D, I), D, duplicateOf(Z, D)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("chainsOfOther")
    @DisplayName("S7A-AC-24 a duplicate chain seen from the other issue puts the link at fault, and no other link")
    void chainOfOther(String name, Link link, UUID other, Link otherHas) {
        Link plain = related(I, P);
        Map<UUID, List<Link>> others = Map.of(other, List.of(link, otherHas), P, List.of(plain));
        assertThat(RestoreLinkCheck.atFault(I, List.of(plain, link), others)).containsExactly(link);
    }

    @Test
    @DisplayName("S7A-AC-24 I is the duplicate of two originals: both links are at fault")
    void duplicateOfTwo() {
        Link toO = duplicateOf(I, O);
        Link toP = duplicateOf(I, P);
        Link plain = related(I, Q);
        assertThat(RestoreLinkCheck.atFault(I, List.of(toO, plain, toP), Map.of())).containsExactly(toO, toP);
    }

    @Test
    @DisplayName("S7A-AC-24 I is a duplicate and an original: both links are at fault, in the order of the links")
    void duplicateAndOriginal() {
        Link toO = duplicateOf(I, O);
        Link fromQ = duplicateOf(Q, I);
        assertThat(RestoreLinkCheck.atFault(I, List.of(fromQ, related(I, P), toO), Map.of()))
                .containsExactly(fromQ, toO);
        assertThat(RestoreLinkCheck.atFault(I, List.of(toO, fromQ), Map.of())).containsExactly(toO, fromQ);
    }

    @Test
    @DisplayName("S7A-AC-24 I is the original of D and a duplicate of Z, with both others in the map")
    void originalThatIsDuplicate() {
        Link fromD = duplicateOf(D, I);
        Link toZ = duplicateOf(I, Z);
        Map<UUID, List<Link>> others = Map.of(D, List.of(fromD), Z, List.of(toZ));
        assertThat(RestoreLinkCheck.atFault(I, List.of(fromD, toZ), others)).containsExactly(fromD, toZ);
    }

    @Test
    @DisplayName("S7A-AC-24 an original may have many duplicates: no chain on either side")
    void manyDuplicatesOfOneOriginal() {
        Link toO = duplicateOf(I, O);
        Map<UUID, List<Link>> ofO = Map.of(O, List.of(duplicateOf(D, O), toO, duplicateOf(W, O)));
        assertThat(RestoreLinkCheck.atFault(I, List.of(toO), ofO)).isEmpty();

        List<Link> originalOfMany = List.of(duplicateOf(D, I), duplicateOf(W, I), duplicateOf(Q, I));
        assertThat(RestoreLinkCheck.atFault(I, originalOfMany, Map.of())).isEmpty();
    }

    @Test
    @DisplayName("S7A-AC-24 a related link makes no chain, whatever the duplicates around it")
    void relatedMakesNoChain() {
        Link toO = related(I, O);
        Map<UUID, List<Link>> others = Map.of(O, List.of(toO, duplicateOf(O, Z), duplicateOf(W, O)));
        assertThat(RestoreLinkCheck.atFault(I, List.of(toO), others)).isEmpty();
    }

    @Test
    @DisplayName("S7A-AC-24 related links of either issue do not join a duplicate link into a chain")
    void relatedAroundDuplicate() {
        Link toO = duplicateOf(I, O);
        List<Link> links = List.of(related(I, P), toO, related(I, Q));
        Map<UUID, List<Link>> others = Map.of(O, List.of(related(O, Z), toO, related(O, W)));
        assertThat(RestoreLinkCheck.atFault(I, links, others)).isEmpty();

        Link fromD = duplicateOf(D, I);
        Map<UUID, List<Link>> ofD = Map.of(D, List.of(related(D, Z), fromD, related(W, D)));
        assertThat(RestoreLinkCheck.atFault(I, List.of(related(I, P), fromD), ofD)).isEmpty();
    }

    @Test
    @DisplayName("S7A-AC-24 links of only related type find nothing, though the others are chained")
    void onlyRelated() {
        List<Link> links = List.of(related(I, O), related(I, D));
        Map<UUID, List<Link>> others = Map.of(
                O, List.of(related(I, O), duplicateOf(O, Z), duplicateOf(W, O)),
                D, List.of(related(I, D), duplicateOf(D, R)));
        assertThat(RestoreLinkCheck.atFault(I, links, others)).isEmpty();
    }

    // ---- each link once, in the order of the links ----

    @Test
    @DisplayName("S7A-AC-24 a link at fault for every reason at once is listed once")
    void everyReasonOnce() {
        Link toO = duplicateOf(I, O);
        Link fromQ = duplicateOf(Q, I);
        List<Link> links = concat(filler(I, 49), List.of(fromQ, toO));
        List<Link> ofO = concat(List.of(toO, duplicateOf(O, Z), duplicateOf(W, O)), filler(O, 48));
        assertThat(ofO).hasSize(51);
        assertThat(RestoreLinkCheck.atFault(I, links, Map.of(O, ofO))).containsExactly(fromQ, toO);
    }

    @Test
    @DisplayName("S7A-AC-24 the links at fault come in the order of the links, each for its own reason")
    void orderOfLinks() {
        Link overOther = related(I, O);
        Link chained = duplicateOf(I, P);
        List<Link> links = concat(filler(I, 0, 5), List.of(overOther), filler(I, 5, 14), List.of(chained),
                filler(I, 19, 31));
        assertThat(links).hasSize(52);
        Map<UUID, List<Link>> others = Map.of(
                O, concat(filler(O, 50), List.of(overOther)),
                P, List.of(chained, duplicateOf(P, Z)));
        assertThat(RestoreLinkCheck.atFault(I, links, others))
                .containsExactly(overOther, chained, links.get(50), links.get(51));
    }
}
