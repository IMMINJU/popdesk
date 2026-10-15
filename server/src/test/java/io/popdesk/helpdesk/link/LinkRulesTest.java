package io.popdesk.helpdesk.link;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.helpdesk.LinkType;
import io.popdesk.helpdesk.link.LinkRules.Decision;
import io.popdesk.helpdesk.link.LinkRules.Link;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Whether a link between two issues can be made and how it is stored (S6C-AC-03 to S6C-AC-06, S6C-AC-13,
 * S6C-AC-14; slice 6c design sections 3 and 7). Written from the Javadoc of {@link LinkRules} alone.
 */
class LinkRulesTest {

    private static final UUID A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID B = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID X = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID Y = UUID.fromString("44444444-4444-4444-4444-444444444444");
    /** Before {@link #HIGH_BIT} in the database's order, after it for {@link UUID#compareTo}. */
    private static final UUID ZERO_ONE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID HIGH_BIT = UUID.fromString("80000000-0000-0000-0000-000000000000");

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

    /** {@code count} related links of {@code issue} to issues met nowhere else. */
    private static List<Link> filler(UUID issue, int count) {
        List<Link> links = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            links.add(related(issue, new UUID(0xF000_0000_0000_0000L, i)));
        }
        return links;
    }

    /**
     * {@code count} links of {@code issue} to issues met nowhere else, every other one a related link and
     * the rest the issue as the original of a duplicate.
     */
    private static List<Link> mixedFiller(UUID issue, int count) {
        List<Link> links = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            UUID stranger = new UUID(0xE000_0000_0000_0000L, i);
            links.add(i % 2 == 0 ? related(issue, stranger) : duplicateOf(stranger, issue));
        }
        return links;
    }

    private static List<Link> with(List<Link> links, Link more) {
        List<Link> all = new ArrayList<>(links);
        all.add(more);
        return all;
    }

    private static void assertRefused(Decision decision, String code) {
        assertThat(decision.errorCode()).isEqualTo(code);
        assertThat(decision.link()).isNull();
    }

    private static void assertStored(Decision decision, Link expected) {
        assertThat(decision.errorCode()).isNull();
        assertThat(decision.link()).isEqualTo(expected);
    }

    // ---- the same issue ----

    @ParameterizedTest
    @EnumSource(LinkType.class)
    @DisplayName("S6C-AC-03 a link from an issue to itself is LINK_TO_SELF, of any type")
    void toSelf(LinkType type) {
        assertRefused(LinkRules.decide(A, A, type, List.of(), List.of()), LinkRules.TO_SELF);
    }

    @ParameterizedTest
    @EnumSource(LinkType.class)
    @DisplayName("S6C-AC-14 LINK_TO_SELF comes before every other check")
    void toSelfFirst(LinkType type) {
        List<Link> full = with(with(filler(A, LinkRules.LIMIT), duplicateOf(A, X)), duplicateOf(Y, A));
        assertRefused(LinkRules.decide(A, A, type, full, full), LinkRules.TO_SELF);
    }

    // ---- a link there already ----

    static Stream<Arguments> existingAndRequested() {
        List<Link> existing = List.of(duplicateOf(A, B), duplicateOf(B, A), related(A, B));
        List<Arguments> cases = new ArrayList<>();
        for (Link link : existing) {
            for (LinkType type : LinkType.values()) {
                cases.add(Arguments.of(link, type));
            }
        }
        return cases.stream();
    }

    @ParameterizedTest
    @MethodSource("existingAndRequested")
    @DisplayName("S6C-AC-04 a link between the two already, of any type and direction, is LINK_EXISTS")
    void exists(Link existing, LinkType type) {
        List<Link> links = List.of(existing);
        assertRefused(LinkRules.decide(A, B, type, links, links), LinkRules.EXISTS);
        assertRefused(LinkRules.decide(B, A, type, links, links), LinkRules.EXISTS);
    }

    @Test
    @DisplayName("S6C-AC-04 a link of either issue to a third issue is not a link between the two")
    void linkToThirdIsNotExisting() {
        Decision decision = LinkRules.decide(A, B, LinkType.RELATED, List.of(related(A, X)), List.of(related(B, X)));
        assertStored(decision, related(A, B));
    }

    @ParameterizedTest
    @EnumSource(LinkType.class)
    @DisplayName("S6C-AC-14 LINK_EXISTS comes before the chain and the limit")
    void existsBeforeChainAndLimit(LinkType type) {
        Link existing = related(A, B);
        // A is a duplicate and an original already, B is a duplicate already, both at the limit
        List<Link> ofA = with(with(with(filler(A, LinkRules.LIMIT - 3), duplicateOf(A, X)), duplicateOf(Y, A)),
                existing);
        List<Link> ofB = with(with(filler(B, LinkRules.LIMIT - 2), duplicateOf(B, X)), existing);
        assertRefused(LinkRules.decide(A, B, type, ofA, ofB), LinkRules.EXISTS);
    }

    // ---- duplicate chains ----

    /**
     * The three chains, each for the duplicate D = A and the original O = B: O is a duplicate already, D
     * is an original already, D is a duplicate already.
     */
    static Stream<Arguments> chains() {
        return Stream.of(
                Arguments.of("the original is a duplicate already", List.of(), List.of(duplicateOf(B, X))),
                Arguments.of("the duplicate is an original already", List.of(duplicateOf(Y, A)), List.of()),
                Arguments.of("the duplicate is a duplicate of another already", List.of(duplicateOf(A, X)),
                        List.of()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("chains")
    @DisplayName("S6C-AC-05 DUPLICATE_OF that makes a chain is LINK_DUPLICATE_CHAIN")
    void chainDuplicateOf(String name, List<Link> ofDuplicate, List<Link> ofOriginal) {
        assertRefused(LinkRules.decide(A, B, LinkType.DUPLICATE_OF, ofDuplicate, ofOriginal),
                LinkRules.DUPLICATE_CHAIN);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("chains")
    @DisplayName("S6C-AC-05 DUPLICATED_BY that makes a chain is LINK_DUPLICATE_CHAIN, from the original's side")
    void chainDuplicatedBy(String name, List<Link> ofDuplicate, List<Link> ofOriginal) {
        assertRefused(LinkRules.decide(B, A, LinkType.DUPLICATED_BY, ofOriginal, ofDuplicate),
                LinkRules.DUPLICATE_CHAIN);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("chains")
    @DisplayName("S6C-AC-05 RELATED is not checked for chains")
    void relatedNotChecked(String name, List<Link> ofA, List<Link> ofB) {
        assertStored(LinkRules.decide(A, B, LinkType.RELATED, ofA, ofB), related(A, B));
        assertStored(LinkRules.decide(B, A, LinkType.RELATED, ofB, ofA), related(A, B));
    }

    @Test
    @DisplayName("S6C-AC-05 an original may have many duplicates: a second duplicate of B is no chain")
    void secondDuplicateOfOriginal() {
        Decision decision = LinkRules.decide(A, B, LinkType.DUPLICATE_OF, List.of(), List.of(duplicateOf(Y, B)));
        assertStored(decision, duplicateOf(A, B));
    }

    @Test
    @DisplayName("S6C-AC-05 related links of either issue make no chain")
    void relatedLinksNoChain() {
        Decision decision = LinkRules.decide(A, B, LinkType.DUPLICATE_OF, List.of(related(A, X)),
                List.of(related(B, Y)));
        assertStored(decision, duplicateOf(A, B));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("chains")
    @DisplayName("S6C-AC-14 LINK_DUPLICATE_CHAIN comes before the limit")
    void chainBeforeLimit(String name, List<Link> ofDuplicate, List<Link> ofOriginal) {
        List<Link> ofA = new ArrayList<>(ofDuplicate);
        ofA.addAll(filler(A, LinkRules.LIMIT));
        List<Link> ofB = new ArrayList<>(ofOriginal);
        ofB.addAll(filler(B, LinkRules.LIMIT));
        assertRefused(LinkRules.decide(A, B, LinkType.DUPLICATE_OF, ofA, ofB), LinkRules.DUPLICATE_CHAIN);
    }

    // ---- the limit ----

    @Test
    @DisplayName("S6C-AC-06 the limit is 50 links")
    void limitIsFifty() {
        assertThat(LinkRules.LIMIT).isEqualTo(50);
    }

    @ParameterizedTest
    @EnumSource(LinkType.class)
    @DisplayName("S6C-AC-06 an issue with 49 links may get one more, on either side")
    void fortyNineAllowed(LinkType type) {
        List<Link> full = filler(A, LinkRules.LIMIT - 1);
        List<Link> fullB = filler(B, LinkRules.LIMIT - 1);
        assertThat(LinkRules.decide(A, B, type, full, List.of()).errorCode()).isNull();
        assertThat(LinkRules.decide(A, B, type, List.of(), fullB).errorCode()).isNull();
        assertThat(LinkRules.decide(A, B, type, full, fullB).errorCode()).isNull();
    }

    @ParameterizedTest
    @EnumSource(LinkType.class)
    @DisplayName("S6C-AC-06 an issue with 50 links is LINK_LIMIT_EXCEEDED, this issue or the other")
    void fiftyRefused(LinkType type) {
        assertRefused(LinkRules.decide(A, B, type, filler(A, LinkRules.LIMIT), List.of()), LinkRules.LIMIT_EXCEEDED);
        assertRefused(LinkRules.decide(A, B, type, List.of(), filler(B, LinkRules.LIMIT)), LinkRules.LIMIT_EXCEEDED);
    }

    @Test
    @DisplayName("S6C-AC-06 more than 50 links, as a restore may meet, is LINK_LIMIT_EXCEEDED too")
    void overFiftyRefused() {
        assertRefused(LinkRules.decide(A, B, LinkType.RELATED, List.of(), filler(B, LinkRules.LIMIT + 5)),
                LinkRules.LIMIT_EXCEEDED);
    }

    @Test
    @DisplayName("S6C-AC-06 every kind of link counts toward the limit, not only related ones")
    void everyKindCounts() {
        // B as the original of each duplicate is no chain for A as B's duplicate
        assertThat(LinkRules.decide(A, B, LinkType.DUPLICATE_OF, List.of(), mixedFiller(B, LinkRules.LIMIT - 1))
                .errorCode()).isNull();
        assertRefused(LinkRules.decide(A, B, LinkType.DUPLICATE_OF, List.of(), mixedFiller(B, LinkRules.LIMIT)),
                LinkRules.LIMIT_EXCEEDED);
        assertRefused(LinkRules.decide(A, B, LinkType.DUPLICATED_BY, mixedFiller(A, LinkRules.LIMIT), List.of()),
                LinkRules.LIMIT_EXCEEDED);
        assertRefused(LinkRules.decide(A, B, LinkType.RELATED, mixedFiller(A, LinkRules.LIMIT), List.of()),
                LinkRules.LIMIT_EXCEEDED);
    }

    // ---- how the link is stored ----

    @ParameterizedTest
    @CsvSource({
        "11111111-1111-1111-1111-111111111111, 22222222-2222-2222-2222-222222222222",
        "22222222-2222-2222-2222-222222222222, 11111111-1111-1111-1111-111111111111",
        "80000000-0000-0000-0000-000000000000, 00000000-0000-0000-0000-000000000001",
        "00000000-0000-0000-0000-000000000001, 80000000-0000-0000-0000-000000000000",
    })
    @DisplayName("S6C-AC-13 DUPLICATE_OF stores the issue as the duplicate, the two in uuid order")
    void storesDuplicateOf(UUID issue, UUID other) {
        Link link = LinkRules.decide(issue, other, LinkType.DUPLICATE_OF, List.of(), List.of()).link();
        assertThat(link.type()).isEqualTo(LinkType.DUPLICATE_OF);
        assertThat(link.duplicateIssueId()).isEqualTo(issue);
        assertThat(before(link.lowIssueId(), link.highIssueId())).isTrue();
        assertThat(List.of(link.lowIssueId(), link.highIssueId())).containsExactlyInAnyOrder(issue, other);
    }

    @ParameterizedTest
    @CsvSource({
        "11111111-1111-1111-1111-111111111111, 22222222-2222-2222-2222-222222222222",
        "22222222-2222-2222-2222-222222222222, 11111111-1111-1111-1111-111111111111",
        "80000000-0000-0000-0000-000000000000, 00000000-0000-0000-0000-000000000001",
        "00000000-0000-0000-0000-000000000001, 80000000-0000-0000-0000-000000000000",
    })
    @DisplayName("S6C-AC-13 DUPLICATED_BY stores the same link as DUPLICATE_OF from the other issue")
    void storesDuplicatedBy(UUID issue, UUID other) {
        Link link = LinkRules.decide(issue, other, LinkType.DUPLICATED_BY, List.of(), List.of()).link();
        assertThat(link).isEqualTo(duplicateOf(other, issue));
        assertThat(link)
                .isEqualTo(LinkRules.decide(other, issue, LinkType.DUPLICATE_OF, List.of(), List.of()).link());
    }

    @ParameterizedTest
    @CsvSource({
        "11111111-1111-1111-1111-111111111111, 22222222-2222-2222-2222-222222222222",
        "22222222-2222-2222-2222-222222222222, 11111111-1111-1111-1111-111111111111",
        "80000000-0000-0000-0000-000000000000, 00000000-0000-0000-0000-000000000001",
        "00000000-0000-0000-0000-000000000001, 80000000-0000-0000-0000-000000000000",
    })
    @DisplayName("S6C-AC-13 RELATED stores no duplicate, the two in uuid order")
    void storesRelated(UUID issue, UUID other) {
        Decision decision = LinkRules.decide(issue, other, LinkType.RELATED, List.of(), List.of());
        assertStored(decision, related(issue, other));
        assertThat(decision.link().duplicateIssueId()).isNull();
    }

    @Test
    @DisplayName("S6C-AC-13 the stored order is the database's, where UUID.compareTo says the other")
    void storedOrderIsDatabaseOrder() {
        assertThat(HIGH_BIT.compareTo(ZERO_ONE)).isNegative();
        Link link = LinkRules.decide(HIGH_BIT, ZERO_ONE, LinkType.RELATED, List.of(), List.of()).link();
        assertThat(link.lowIssueId()).isEqualTo(ZERO_ONE);
        assertThat(link.highIssueId()).isEqualTo(HIGH_BIT);
    }

    // ---- uuid order ----

    @ParameterizedTest
    @CsvSource({
        "00000000-0000-0000-0000-000000000001, 80000000-0000-0000-0000-000000000000",
        "00000000-0000-0000-0000-000000000000, 00000000-0000-0000-8000-000000000000",
        "7fffffff-ffff-ffff-ffff-ffffffffffff, 80000000-0000-0000-0000-000000000000",
        "00000000-0000-0000-7fff-ffffffffffff, 00000000-0000-0000-8000-000000000000",
        "11111111-1111-1111-1111-111111111111, 22222222-2222-2222-2222-222222222222",
        "00000000-0000-0000-0000-000000000000, ffffffff-ffff-ffff-ffff-ffffffffffff",
        "12345678-0000-0000-0000-000000000000, 12345678-0000-0001-0000-000000000000",
        "aaaaaaaa-0000-0000-0000-000000000000, bbbbbbbb-0000-0000-0000-000000000000",
    })
    @DisplayName("S6C-AC-04 compare orders uuids byte by byte, unsigned, as the database does")
    void compareUnsigned(UUID earlier, UUID later) {
        assertThat(LinkRules.compare(earlier, later)).isNegative();
        assertThat(LinkRules.compare(later, earlier)).isPositive();
    }

    @Test
    @DisplayName("S6C-AC-04 compare disagrees with UUID.compareTo where the high bit is set")
    void compareDisagreesWithSigned() {
        assertThat(HIGH_BIT.compareTo(ZERO_ONE)).isNegative();
        assertThat(LinkRules.compare(HIGH_BIT, ZERO_ONE)).isPositive();
        UUID lowHigh = UUID.fromString("00000000-0000-0000-8000-000000000000");
        UUID lowOne = UUID.fromString("00000000-0000-0000-0000-000000000001");
        assertThat(lowHigh.compareTo(lowOne)).isNegative();
        assertThat(LinkRules.compare(lowHigh, lowOne)).isPositive();
    }

    @Test
    @DisplayName("S6C-AC-04 compare of equal uuids is zero")
    void compareEqual() {
        assertThat(LinkRules.compare(A, UUID.fromString(A.toString()))).isZero();
        assertThat(LinkRules.compare(HIGH_BIT, UUID.fromString(HIGH_BIT.toString()))).isZero();
    }

    // ---- a stored link seen from one issue ----

    @Test
    @DisplayName("S6C-AC-13 a duplicate link is DUPLICATE_OF on the duplicate and DUPLICATED_BY on the original")
    void seenFromDuplicate() {
        Link aOfB = duplicateOf(A, B);
        assertThat(aOfB.seenFrom(A)).isEqualTo(LinkType.DUPLICATE_OF);
        assertThat(aOfB.seenFrom(B)).isEqualTo(LinkType.DUPLICATED_BY);
        Link bOfA = duplicateOf(B, A);
        assertThat(bOfA.seenFrom(B)).isEqualTo(LinkType.DUPLICATE_OF);
        assertThat(bOfA.seenFrom(A)).isEqualTo(LinkType.DUPLICATED_BY);
    }

    @Test
    @DisplayName("S6C-AC-13 a related link is RELATED on either issue")
    void seenFromRelated() {
        Link link = related(A, B);
        assertThat(link.seenFrom(A)).isEqualTo(LinkType.RELATED);
        assertThat(link.seenFrom(B)).isEqualTo(LinkType.RELATED);
    }

    @Test
    @DisplayName("S6C-AC-13 otherThan gives the issue that is not the one given")
    void otherThan() {
        Link link = related(A, HIGH_BIT);
        assertThat(link.otherThan(A)).isEqualTo(HIGH_BIT);
        assertThat(link.otherThan(HIGH_BIT)).isEqualTo(A);
        Link duplicate = duplicateOf(B, A);
        assertThat(duplicate.otherThan(A)).isEqualTo(B);
        assertThat(duplicate.otherThan(B)).isEqualTo(A);
    }

    @Test
    @DisplayName("S6C-AC-04 joins holds for the two issues in either order and for no other pair")
    void joins() {
        Link link = duplicateOf(A, B);
        assertThat(link.joins(A, B)).isTrue();
        assertThat(link.joins(B, A)).isTrue();
        assertThat(link.joins(A, X)).isFalse();
        assertThat(link.joins(X, B)).isFalse();
        assertThat(link.joins(X, Y)).isFalse();
        assertThat(link.joins(A, A)).isFalse();
    }

    @Test
    @DisplayName("S6C-AC-05 hasDuplicate is true only for the duplicate of a duplicate link")
    void hasDuplicate() {
        Link aOfB = duplicateOf(A, B);
        assertThat(aOfB.hasDuplicate(A)).isTrue();
        assertThat(aOfB.hasDuplicate(B)).isFalse();
        Link bOfA = duplicateOf(B, A);
        assertThat(bOfA.hasDuplicate(B)).isTrue();
        assertThat(bOfA.hasDuplicate(A)).isFalse();
        Link related = related(A, B);
        assertThat(related.hasDuplicate(A)).isFalse();
        assertThat(related.hasDuplicate(B)).isFalse();
    }

    @Test
    @DisplayName("S6C-AC-05 hasOriginal is true only for the original of a duplicate link")
    void hasOriginal() {
        Link aOfB = duplicateOf(A, B);
        assertThat(aOfB.hasOriginal(B)).isTrue();
        assertThat(aOfB.hasOriginal(A)).isFalse();
        Link bOfA = duplicateOf(B, A);
        assertThat(bOfA.hasOriginal(A)).isTrue();
        assertThat(bOfA.hasOriginal(B)).isFalse();
        Link related = related(A, B);
        assertThat(related.hasOriginal(A)).isFalse();
        assertThat(related.hasOriginal(B)).isFalse();
    }
}
