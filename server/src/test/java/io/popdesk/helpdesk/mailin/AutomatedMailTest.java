package io.popdesk.helpdesk.mailin;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.helpdesk.mailin.AutomatedMail.Dmarc;
import io.popdesk.helpdesk.mailin.AutomatedMail.Reason;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which received emails are discarded before they are read as a customer's (S12-AC-11, S12-AC-12; MAILIN-7, D-68).
 * Written from the Javadoc of {@link AutomatedMail} and the acceptance text alone.
 */
class AutomatedMailTest {

    private static final String SENDING = "popdesk@example.com";
    private static final String INBOUND = "support@example.com";
    private static final String CUSTOMER = "kim@customer.example";

    /** Headers by name in any letter case, as the caller gives them; pairs of name and raw value. */
    private static Function<String, List<String>> headers(String... nameValue) {
        Map<String, List<String>> map = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (int i = 0; i < nameValue.length; i += 2) {
            map.computeIfAbsent(nameValue[i], k -> new ArrayList<>()).add(nameValue[i + 1]);
        }
        return name -> map.getOrDefault(name, List.of());
    }

    private static Optional<Reason> reason(Function<String, List<String>> headers) {
        return AutomatedMail.reason(headers, "text/plain; charset=UTF-8", CUSTOMER, SENDING, INBOUND);
    }

    private static Optional<Reason> header(String name, String value) {
        return reason(headers(name, value));
    }

    // ---- a customer's mail (S12-AC-11) ----

    @Test
    @DisplayName("S12-AC-11: a customer's mail without any of the headers is kept")
    void s12Ac11_plainCustomerMailIsKept() {
        assertThat(reason(headers())).isEmpty();
        assertThat(reason(headers("Subject", "Help", "Precedence", "first-class"))).isEmpty();
        assertThat(AutomatedMail.reason(headers(), null, CUSTOMER, SENDING, INBOUND)).isEmpty();
    }

    // ---- Auto-Submitted ----

    @Test
    @DisplayName("S12-AC-11: an Auto-Submitted other than no is automated, in any letter case and with parameters")
    void s12Ac11_autoSubmittedNotNoIsAutomated() {
        for (String value : List.of("auto-replied", "auto-generated", "auto-notified", "AUTO-REPLIED",
                "auto-replied; owner-email=\"a@example.com\"", "auto-generated (by a robot)", "")) {
            assertThat(header("Auto-Submitted", value)).as(value).contains(Reason.AUTOMATED);
        }
    }

    @Test
    @DisplayName("S12-AC-11: Auto-Submitted no is kept, in any letter case, with spaces, a comment, a ; or folded")
    void s12Ac11_autoSubmittedNoIsKept() {
        for (String value : List.of("no", "No", "NO", "  no  ", "no (written by a person)", "no; x=y",
                "no\r\n (folded comment)", "(a comment) no")) {
            assertThat(header("Auto-Submitted", value)).as(value).isEmpty();
        }
    }

    @Test
    @DisplayName("S12-AC-11: one Auto-Submitted other than no among several is automated")
    void s12Ac11_anyAutoSubmittedCounts() {
        assertThat(reason(headers("Auto-Submitted", "no", "Auto-Submitted", "auto-replied")))
                .contains(Reason.AUTOMATED);
    }

    // ---- Precedence ----

    @Test
    @DisplayName("S12-AC-11: Precedence bulk, list, junk and auto_reply are automated, in any letter case")
    void s12Ac11_bulkPrecedenceIsAutomated() {
        for (String value : List.of("bulk", "list", "junk", "auto_reply", "BULK", "List", " junk ",
                "bulk (newsletter)", "list; x")) {
            assertThat(header("Precedence", value)).as(value).contains(Reason.AUTOMATED);
        }
    }

    @Test
    @DisplayName("S12-AC-11: another Precedence is kept")
    void s12Ac11_otherPrecedenceIsKept() {
        for (String value : List.of("first-class", "normal", "bulky", "listing", "auto-reply", "")) {
            assertThat(header("Precedence", value)).as(value).isEmpty();
        }
        assertThat(reason(headers("Precedence", "normal", "Precedence", "bulk"))).contains(Reason.AUTOMATED);
    }

    // ---- X-Autoreply, X-Autorespond ----

    @Test
    @DisplayName("S12-AC-11: an X-Autoreply or X-Autorespond header is automated whatever its value")
    void s12Ac11_autoreplyHeadersAreAutomated() {
        assertThat(header("X-Autoreply", "yes")).contains(Reason.AUTOMATED);
        assertThat(header("X-Autoreply", "")).contains(Reason.AUTOMATED);
        assertThat(header("X-Autorespond", "yes")).contains(Reason.AUTOMATED);
        assertThat(header("X-Autorespond", "")).contains(Reason.AUTOMATED);
    }

    // ---- Content-Type ----

    @Test
    @DisplayName("S12-AC-11: a top Content-Type of multipart/report is automated, in any letter case")
    void s12Ac11_multipartReportIsAutomated() {
        for (String type : List.of("multipart/report; report-type=delivery-status; boundary=\"b\"",
                "multipart/report", "Multipart/Report; report-type=disposition-notification")) {
            assertThat(AutomatedMail.reason(headers(), type, CUSTOMER, SENDING, INBOUND)).as(type)
                    .contains(Reason.AUTOMATED);
        }
    }

    @Test
    @DisplayName("S12-AC-11: another top Content-Type, or none, is kept")
    void s12Ac11_otherContentTypeIsKept() {
        for (String type : List.of("multipart/mixed; boundary=\"b\"", "text/plain", "multipart/alternative")) {
            assertThat(AutomatedMail.reason(headers(), type, CUSTOMER, SENDING, INBOUND)).as(type).isEmpty();
        }
        assertThat(AutomatedMail.reason(headers(), null, CUSTOMER, SENDING, INBOUND)).isEmpty();
    }

    // ---- Return-Path ----

    @Test
    @DisplayName("S12-AC-11: a Return-Path of <> is automated, with spaces or a comment around it")
    void s12Ac11_nullReturnPathIsAutomated() {
        for (String value : List.of("<>", " <> ", "< >", "<> (bounce)", "<>\r\n")) {
            assertThat(header("Return-Path", value)).as(value).contains(Reason.AUTOMATED);
        }
        assertThat(reason(headers("Return-Path", "<a@example.com>", "Return-Path", "<>")))
                .contains(Reason.AUTOMATED);
    }

    @Test
    @DisplayName("S12-AC-11: a Return-Path with an address is kept")
    void s12Ac11_returnPathWithAddressIsKept() {
        assertThat(header("Return-Path", "<kim@customer.example>")).isEmpty();
        assertThat(header("Return-Path", "<<>>")).isEmpty();
    }

    // ---- From ----

    @Test
    @DisplayName("S12-AC-11: a From of the sending address is automated, in any letter case")
    void s12Ac11_fromSendingAddressIsAutomated() {
        assertThat(AutomatedMail.reason(headers(), null, "popdesk@example.com", SENDING, INBOUND))
                .contains(Reason.AUTOMATED);
        assertThat(AutomatedMail.reason(headers(), null, "PopDesk@EXAMPLE.com", SENDING, INBOUND))
                .contains(Reason.AUTOMATED);
        assertThat(AutomatedMail.reason(headers(), null, "popdesk@example.com", "Popdesk@Example.COM", null))
                .contains(Reason.AUTOMATED);
    }

    @Test
    @DisplayName("S12-AC-11: a From of the inbound address, also with +code, is automated, in any letter case")
    void s12Ac11_fromInboundAddressIsAutomated() {
        for (String from : List.of("support@example.com", "SUPPORT@Example.Com", "support+ACME@example.com",
                "Support+acme@EXAMPLE.COM")) {
            assertThat(AutomatedMail.reason(headers(), null, from, SENDING, INBOUND)).as(from)
                    .contains(Reason.AUTOMATED);
        }
        assertThat(AutomatedMail.reason(headers(), null, "support+ACME@example.com", null, INBOUND))
                .contains(Reason.AUTOMATED);
    }

    @Test
    @DisplayName("S12-AC-11: a From of another address is kept, also next to the two addresses")
    void s12Ac11_fromOtherAddressIsKept() {
        for (String from : List.of(CUSTOMER, "popdesk@example.org", "popdesk2@example.com",
                "support@example.org", "supportx@example.com", "help+ACME@example.com")) {
            assertThat(AutomatedMail.reason(headers(), null, from, SENDING, INBOUND)).as(from).isEmpty();
        }
    }

    @Test
    @DisplayName("S12-AC-11: without the two addresses, or without one From address, only the headers count")
    void s12Ac11_noAddressesLooksAtHeadersOnly() {
        assertThat(AutomatedMail.reason(headers(), null, "popdesk@example.com", null, null)).isEmpty();
        assertThat(AutomatedMail.reason(headers(), null, null, SENDING, INBOUND)).isEmpty();
        assertThat(AutomatedMail.reason(headers("Precedence", "bulk"), null, null, SENDING, INBOUND))
                .contains(Reason.AUTOMATED);
    }

    // ---- DMARC (S12-AC-12) ----

    @Test
    @DisplayName("S12-AC-12: dmarc=fail in Authentication-Results is DMARC_FAIL")
    void s12Ac12_dmarcFailIsDiscarded() {
        assertThat(header("Authentication-Results", "mx.example.com; dmarc=fail (p=reject) header.from=x.example"))
                .contains(Reason.DMARC_FAIL);
    }

    @Test
    @DisplayName("S12-AC-12: dmarc=pass, none, or no result is kept")
    void s12Ac12_otherDmarcIsKept() {
        assertThat(header("Authentication-Results", "mx.example.com; dmarc=pass header.from=x.example")).isEmpty();
        assertThat(header("Authentication-Results", "mx.example.com; dmarc=none")).isEmpty();
        assertThat(header("Authentication-Results", "mx.example.com; spf=fail")).isEmpty();
    }

    @Test
    @DisplayName("S12-AC-12: other headers that look like Authentication-Results are not read")
    void s12Ac12_otherHeaderNamesAreNotRead() {
        assertThat(header("ARC-Authentication-Results", "i=1; mx.example.com; dmarc=fail")).isEmpty();
        assertThat(header("X-MS-Exchange-Authentication-Results", "mx.example.com; dmarc=fail")).isEmpty();
    }

    @Test
    @DisplayName("S12-AC-11, 12: an automated mail that also fails DMARC is AUTOMATED, the reason of S12-AC-11 first")
    void s12Ac11_automatedComesBeforeDmarc() {
        assertThat(reason(headers("Auto-Submitted", "auto-replied", "Authentication-Results", "mx; dmarc=fail")))
                .contains(Reason.AUTOMATED);
    }

    @Test
    @DisplayName("S12-AC-12: dmarc reads method=result after the server's name, spaces around = and a version allowed")
    void s12Ac12_dmarcReadsResults() {
        assertThat(AutomatedMail.dmarc(List.of("mx.example.com; dmarc=fail"))).isEqualTo(Dmarc.FAIL);
        assertThat(AutomatedMail.dmarc(List.of("mx.example.com;dmarc=fail"))).isEqualTo(Dmarc.FAIL);
        assertThat(AutomatedMail.dmarc(List.of("mx.example.com; dmarc = fail"))).isEqualTo(Dmarc.FAIL);
        assertThat(AutomatedMail.dmarc(List.of("mx.example.com; dmarc/1=fail"))).isEqualTo(Dmarc.FAIL);
        assertThat(AutomatedMail.dmarc(List.of("mx.example.com 1; spf=pass smtp.mailfrom=x.example; "
                + "dkim=pass header.d=x.example; dmarc=fail (p=reject dis=none) header.from=x.example")))
                .isEqualTo(Dmarc.FAIL);
        assertThat(AutomatedMail.dmarc(List.of("mx.example.com; dmarc=pass header.from=x.example")))
                .isEqualTo(Dmarc.PASS);
    }

    @Test
    @DisplayName("S12-AC-12: method and result ignore letter case")
    void s12Ac12_dmarcIgnoresLetterCase() {
        assertThat(AutomatedMail.dmarc(List.of("mx; DMARC=FAIL"))).isEqualTo(Dmarc.FAIL);
        assertThat(AutomatedMail.dmarc(List.of("mx; Dmarc=Fail"))).isEqualTo(Dmarc.FAIL);
        assertThat(AutomatedMail.dmarc(List.of("mx; DMARC=Pass"))).isEqualTo(Dmarc.PASS);
    }

    @Test
    @DisplayName("S12-AC-12: a folded header is unfolded and comments are taken out before it is read")
    void s12Ac12_dmarcUnfoldsAndDropsComments() {
        assertThat(AutomatedMail.dmarc(List.of("mx.example.com;\r\n\tdmarc=fail"))).isEqualTo(Dmarc.FAIL);
        assertThat(AutomatedMail.dmarc(List.of("mx.example.com;\r\n dmarc\r\n =\r\n fail"))).isEqualTo(Dmarc.FAIL);
        assertThat(AutomatedMail.dmarc(List.of("mx.example.com; (checked) dmarc=fail"))).isEqualTo(Dmarc.FAIL);
        assertThat(AutomatedMail.dmarc(List.of("mx.example.com (a; b); dmarc=fail"))).isEqualTo(Dmarc.FAIL);
        assertThat(AutomatedMail.dmarc(List.of("mx.example.com; spf=pass (dmarc=fail)"))).isEqualTo(Dmarc.NONE);
        assertThat(AutomatedMail.dmarc(List.of("mx.example.com; spf=pass (x; dmarc=fail)"))).isEqualTo(Dmarc.NONE);
    }

    @Test
    @DisplayName("S12-AC-12: the first part is the server's name and is not read as a result")
    void s12Ac12_dmarcSkipsServerName() {
        assertThat(AutomatedMail.dmarc(List.of("dmarc=fail"))).isEqualTo(Dmarc.NONE);
        assertThat(AutomatedMail.dmarc(List.of("dmarc=fail; spf=pass"))).isEqualTo(Dmarc.NONE);
    }

    @Test
    @DisplayName("S12-AC-12: none, temperror, permerror, another word, another method or no result is NONE")
    void s12Ac12_dmarcWithoutAResultIsNone() {
        assertThat(AutomatedMail.dmarc(List.of())).isEqualTo(Dmarc.NONE);
        assertThat(AutomatedMail.dmarc(List.of(""))).isEqualTo(Dmarc.NONE);
        assertThat(AutomatedMail.dmarc(List.of("mx.example.com; none"))).isEqualTo(Dmarc.NONE);
        for (String result : List.of("none", "temperror", "permerror", "softfail", "failed", "passed")) {
            assertThat(AutomatedMail.dmarc(List.of("mx.example.com; dmarc=" + result))).as(result)
                    .isEqualTo(Dmarc.NONE);
        }
        assertThat(AutomatedMail.dmarc(List.of("mx.example.com; spf=fail; dkim=fail"))).isEqualTo(Dmarc.NONE);
        assertThat(AutomatedMail.dmarc(List.of("mx.example.com; dmarcx=fail"))).isEqualTo(Dmarc.NONE);
        assertThat(AutomatedMail.dmarc(List.of("mx.example.com; xdmarc=fail"))).isEqualTo(Dmarc.NONE);
        assertThat(AutomatedMail.dmarc(List.of("mx.example.com; dkim=pass header.dmarc=fail"))).isEqualTo(Dmarc.NONE);
    }

    @Test
    @DisplayName("S12-AC-12: one fail in any header or part is FAIL; a pass without a fail is PASS")
    void s12Ac12_oneFailAnywhereIsFail() {
        assertThat(AutomatedMail.dmarc(List.of("a.example; dmarc=pass", "b.example; dmarc=fail")))
                .isEqualTo(Dmarc.FAIL);
        assertThat(AutomatedMail.dmarc(List.of("a.example; dmarc=fail", "b.example; dmarc=pass")))
                .isEqualTo(Dmarc.FAIL);
        assertThat(AutomatedMail.dmarc(List.of("a.example; dmarc=pass; dmarc=fail"))).isEqualTo(Dmarc.FAIL);
        assertThat(AutomatedMail.dmarc(List.of("a.example; dmarc=none", "b.example; dmarc=pass")))
                .isEqualTo(Dmarc.PASS);
        assertThat(AutomatedMail.dmarc(List.of("a.example; dmarc=pass", "b.example; dmarc=none")))
                .isEqualTo(Dmarc.PASS);
        assertThat(AutomatedMail.dmarc(List.of("a.example; dmarc=pass; dmarc=temperror"))).isEqualTo(Dmarc.PASS);
    }

    // ---- unfold, withoutComments ----

    @Test
    @DisplayName("unfold takes out the line breaks of folding and keeps the space after them")
    void unfoldKeepsTheSpace() {
        assertThat(AutomatedMail.unfold("a\r\n b")).isEqualTo("a b");
        assertThat(AutomatedMail.unfold("a\r\n\tb\n c")).isEqualTo("a\tb c");
        assertThat(AutomatedMail.unfold("plain")).isEqualTo("plain");
    }

    @Test
    @DisplayName("withoutComments takes out comments, nested or with \\-quoted characters, outside quoted strings")
    void withoutCommentsTakesThemOut() {
        assertThat(spaced(AutomatedMail.withoutComments("a (b) c"))).isEqualTo("a c");
        assertThat(spaced(AutomatedMail.withoutComments("a (b (c) d) e"))).isEqualTo("a e");
        assertThat(spaced(AutomatedMail.withoutComments("a (b \\) c) d"))).isEqualTo("a d");
        assertThat(spaced(AutomatedMail.withoutComments("a (b \\( c) d"))).isEqualTo("a d");
        assertThat(spaced(AutomatedMail.withoutComments("a (unclosed comment"))).isEqualTo("a");
        assertThat(spaced(AutomatedMail.withoutComments("a (b (c) d"))).isEqualTo("a");
        assertThat(AutomatedMail.withoutComments("no comment")).isEqualTo("no comment");
    }

    @Test
    @DisplayName("withoutComments keeps parentheses inside a quoted string, also after a \\-quoted quote")
    void withoutCommentsKeepsQuotedStrings() {
        assertThat(AutomatedMail.withoutComments("\"(kept)\" x")).isEqualTo("\"(kept)\" x");
        assertThat(AutomatedMail.withoutComments("\"a\\\"(kept)\" (gone)")).contains("(kept)").doesNotContain("gone");
        assertThat(spaced(AutomatedMail.withoutComments("(a \"b) c"))).isEqualTo("c");
        assertThat(AutomatedMail.withoutComments("\"a\\\"b\" x")).isEqualTo("\"a\\\"b\" x");
        assertThat(AutomatedMail.withoutComments("\"a\\")).isEqualTo("\"a\\");
    }

    @Test
    @DisplayName("withoutComments: a \\ outside a quoted string or comment quotes nothing; a lone ) is kept")
    void withoutCommentsOutsideQuotes() {
        assertThat(spaced(AutomatedMail.withoutComments("a\\(b) c"))).isEqualTo("a\\ c");
        assertThat(AutomatedMail.withoutComments("a ) b")).isEqualTo("a ) b");
        assertThat(spaced(AutomatedMail.withoutComments("a ) b (c) d"))).isEqualTo("a ) b d");
    }

    private static String spaced(String s) {
        return s.replaceAll("\\s+", " ").strip();
    }
}
