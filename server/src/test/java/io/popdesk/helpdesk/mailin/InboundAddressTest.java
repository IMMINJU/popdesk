package io.popdesk.helpdesk.mailin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The company code a customer adds to {@code MAIL_INBOUND_ADDRESS} as {@code +CODE} (S12-AC-19, D-16), and whether an
 * address is that address (S12-AC-11). Written from the Javadoc of {@link InboundAddress} and the acceptance text
 * alone.
 */
class InboundAddressTest {

    private static final String INBOUND = "support@example.com";

    // ---- code (S12-AC-19) ----

    @Test
    @DisplayName("S12-AC-19: the code after + of the inbound address, in upper case")
    void s12Ac19_codeAfterPlus() {
        assertThat(InboundAddress.code(List.of("support+ACME@example.com"), INBOUND)).contains("ACME");
        assertThat(InboundAddress.code(List.of("support+acme@example.com"), INBOUND)).contains("ACME");
        assertThat(InboundAddress.code(List.of("support+Ac2e@example.com"), INBOUND)).contains("AC2E");
    }

    @Test
    @DisplayName("S12-AC-19: the local part and the domain are compared without letter case, spaces around cut")
    void s12Ac19_comparedWithoutCase() {
        assertThat(InboundAddress.code(List.of("SUPPORT+acme@EXAMPLE.COM"), INBOUND)).contains("ACME");
        assertThat(InboundAddress.code(List.of("support+acme@example.com"), "Support@Example.Com")).contains("ACME");
        assertThat(InboundAddress.code(List.of(" support+acme@example.com "), INBOUND)).contains("ACME");
    }

    @Test
    @DisplayName("S12-AC-19: the domain is compared as its IDN A-label")
    void s12Ac19_domainAsALabel() {
        assertThat(InboundAddress.code(List.of("support+ACME@xn--bcher-kva.example"), "support@b\u00fccher.example"))
                .contains("ACME");
        assertThat(InboundAddress.code(List.of("support+ACME@B\u00dcCHER.example"), "support@xn--bcher-kva.example"))
                .contains("ACME");
    }

    @Test
    @DisplayName("S12-AC-19: addresses without +, at another local part or another domain are passed over")
    void s12Ac19_othersArePassedOver() {
        assertThat(InboundAddress.code(List.of("support@example.com"), INBOUND)).isEmpty();
        assertThat(InboundAddress.code(List.of("help+ACME@example.com"), INBOUND)).isEmpty();
        assertThat(InboundAddress.code(List.of("supportx+ACME@example.com"), INBOUND)).isEmpty();
        assertThat(InboundAddress.code(List.of("suppor+ACME@example.com"), INBOUND)).isEmpty();
        assertThat(InboundAddress.code(List.of("support+ACME@example.org"), INBOUND)).isEmpty();
        assertThat(InboundAddress.code(List.of("support+ACME@mail.example.com"), INBOUND)).isEmpty();
        assertThat(InboundAddress.code(List.of("support@exa+mple.com"), INBOUND)).isEmpty();
        assertThat(InboundAddress.code(List.of("support+ACME"), INBOUND)).isEmpty();
        assertThat(InboundAddress.code(List.of(), INBOUND)).isEmpty();
    }

    @Test
    @DisplayName("S12-AC-19: the first address with a code counts, in the order given")
    void s12Ac19_firstAddressCounts() {
        assertThat(InboundAddress.code(List.of("support+ACME@example.com", "support+BETA@example.com"), INBOUND))
                .contains("ACME");
        assertThat(InboundAddress.code(List.of("kim@customer.example", "support@example.com",
                "help+ACME@example.com", "support+BETA@example.com"), INBOUND)).contains("BETA");
        assertThat(InboundAddress.code(List.of("support+no-code@example.com", "support+BETA@example.com"), INBOUND))
                .contains(InboundAddress.NO_SUCH_CODE);
    }

    @Test
    @DisplayName("S12-AC-19: anything after + that is not of the code form is no such code")
    void s12Ac19_notCodeFormIsNoSuchCode() {
        for (String address : List.of("support+A@example.com", "support+ABCDEFGHIJK@example.com",
                "support+AC-ME@example.com", "support+ACME+X@example.com", "support+\u00c4CME@example.com",
                "support+@example.com", "support+AC ME@example.com")) {
            assertThat(InboundAddress.code(List.of(address), INBOUND)).as(address)
                    .contains(InboundAddress.NO_SUCH_CODE);
        }
    }

    @Test
    @DisplayName("S12-AC-19: the bounds of the code form, 2 and 10 letters or digits")
    void s12Ac19_codeFormBounds() {
        assertThat(InboundAddress.code(List.of("support+AB@example.com"), INBOUND)).contains("AB");
        assertThat(InboundAddress.code(List.of("support+ABCDEFGHIJ@example.com"), INBOUND)).contains("ABCDEFGHIJ");
        assertThat(InboundAddress.code(List.of("support+09@example.com"), INBOUND)).contains("09");
    }

    @Test
    @DisplayName("S12-AC-19: without an inbound address there is no code")
    void s12Ac19_noInboundAddress() {
        assertThat(InboundAddress.code(List.of("support+ACME@example.com"), null)).isEmpty();
    }

    // ---- isInbound (S12-AC-11) ----

    @Test
    @DisplayName("S12-AC-11: the inbound address, and it with + and anything after it, is inbound")
    void s12Ac11_isInbound() {
        for (String address : List.of("support@example.com", "SUPPORT@Example.COM", " support@example.com ",
                "support+ACME@example.com", "support+anything-at.all@example.com", "support+@example.com",
                "Support+x+y@EXAMPLE.com")) {
            assertThat(InboundAddress.isInbound(address, INBOUND)).as(address).isTrue();
        }
        assertThat(InboundAddress.isInbound("support@example.com", " Support@Example.com ")).isTrue();
        assertThat(InboundAddress.isInbound("support+x@xn--bcher-kva.example", "support@b\u00fccher.example")).isTrue();
    }

    @Test
    @DisplayName("S12-AC-11: another local part, another domain, or no inbound address is not inbound")
    void s12Ac11_isNotInbound() {
        for (String address : List.of("help@example.com", "supportx@example.com", "suppor@example.com",
                "support@example.org", "support+ACME@example.org", "support@exa+mple.com", "support",
                "x+support@example.com", "kim@customer.example")) {
            assertThat(InboundAddress.isInbound(address, INBOUND)).as(address).isFalse();
        }
        assertThat(InboundAddress.isInbound("support@example.com", null)).isFalse();
    }

    @Test
    @DisplayName("S12-AC-11: letter case is ASCII only in the local part")
    void s12Ac11_localPartCaseIsAsciiOnly() {
        assertThat(InboundAddress.isInbound("S\u00dcPPORT@example.com", "s\u00fcpport@example.com")).isFalse();
        assertThat(InboundAddress.isInbound("s\u00fcpport@example.com", "s\u00fcpport@example.com")).isTrue();
    }

    // ---- normal ----

    @Test
    @DisplayName("normal: trimmed, local part in ASCII lower case, domain as its A-label in lower case")
    void normalForm() {
        assertThat(InboundAddress.normal(" Foo.Bar@Example.COM ")).isEqualTo("foo.bar@example.com");
        assertThat(InboundAddress.normal("a@B\u00dcCHER.Example")).isEqualTo("a@xn--bcher-kva.example");
        assertThat(InboundAddress.normal("\u00c4B@x.example")).isEqualTo("\u00c4b@x.example");
        assertThat(InboundAddress.normal("AZ@x.example")).isEqualTo("az@x.example");
        assertThat(InboundAddress.normal("@[\\]^_`{@x.example")).isEqualTo("@[\\]^_`{@x.example");
    }

    @Test
    @DisplayName("normal: an address without @ is all local part")
    void normalWithoutAt() {
        assertThat(InboundAddress.normal(" FOO.Bar ")).isEqualTo("foo.bar");
        assertThat(InboundAddress.normal("B\u00dcCHER")).isEqualTo("b\u00dccher");
    }

    @Test
    @DisplayName("normal: the domain is an A-label also when the local part is empty")
    void normalWithEmptyLocalPart() {
        assertThat(InboundAddress.normal("@B\u00dcCHER.example")).isEqualTo("@xn--bcher-kva.example");
    }

    @Test
    @DisplayName("normal: the last @ splits the address")
    void normalSplitsAtLastAt() {
        assertThat(InboundAddress.normal("\"A@B\"@Example.com")).isEqualTo("\"a@b\"@example.com");
    }

    @Test
    @DisplayName("normal: a domain that cannot be an A-label is left as it is, in lower case")
    void normalKeepsDomainIdnCannotWrite() {
        String longLabel = "\u00dc".repeat(64);
        assertThat(InboundAddress.normal("A@" + longLabel + ".example"))
                .isEqualTo("a@" + "\u00fc".repeat(64) + ".example");
    }
}
