package io.popdesk.helpdesk.mailin;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.helpdesk.issue.IssueKey;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The issue key a reply carries in its subject as {@code [KEY]} (S12-AC-16, MAILIN-2). Written from the Javadoc of
 * {@link IssueKeyInSubject} and the acceptance text alone.
 */
class IssueKeyInSubjectTest {

    @Test
    @DisplayName("S12-AC-16: null or empty subject gives no key")
    void s12Ac16_nullOrEmptyGivesNothing() {
        assertThat(IssueKeyInSubject.key(null)).isEmpty();
        assertThat(IssueKeyInSubject.key("")).isEmpty();
        assertThat(IssueKeyInSubject.key("Re: hello")).isEmpty();
    }

    @Test
    @DisplayName("S12-AC-16: the key in square brackets anywhere in the subject")
    void s12Ac16_keyInBrackets() {
        assertThat(IssueKeyInSubject.key("Re: [ACME-7] The printer")).contains(new IssueKey("ACME", 7));
        assertThat(IssueKeyInSubject.key("[ACME-7]")).contains(new IssueKey("ACME", 7));
        assertThat(IssueKeyInSubject.key("RE: RE: The printer [B2B-123456789]"))
                .contains(new IssueKey("B2B", 123456789));
        assertThat(IssueKeyInSubject.key("x[AB-1]y")).contains(new IssueKey("AB", 1));
    }

    @Test
    @DisplayName("S12-AC-16: the key in any letter case, given in upper case")
    void s12Ac16_anyLetterCase() {
        assertThat(IssueKeyInSubject.key("Re: [acme-7]")).contains(new IssueKey("ACME", 7));
        assertThat(IssueKeyInSubject.key("Re: [AcMe-42]")).contains(new IssueKey("ACME", 42));
        assertThat(IssueKeyInSubject.key("[az-1]")).contains(new IssueKey("AZ", 1));
        assertThat(IssueKeyInSubject.key("[ACME-7]").orElseThrow().toString()).isEqualTo("ACME-7");
    }

    @Test
    @DisplayName("S12-AC-16: the first bracket of the key form counts, others before it are passed over")
    void s12Ac16_firstOfKeyFormCounts() {
        assertThat(IssueKeyInSubject.key("[ACME-1] [BETA-2]")).contains(new IssueKey("ACME", 1));
        assertThat(IssueKeyInSubject.key("[CODE-0] [ACME-3]")).contains(new IssueKey("ACME", 3));
        assertThat(IssueKeyInSubject.key("[External] Re: [ACME-3]")).contains(new IssueKey("ACME", 3));
        assertThat(IssueKeyInSubject.key("[ ACME-1 ] [BETA-2]")).contains(new IssueKey("BETA", 2));
    }

    @Test
    @DisplayName("S12-AC-16: brackets around anything not exactly of the key form give nothing")
    void s12Ac16_notOfKeyForm() {
        for (String subject : List.of("ACME-7", "[CODE-0]", "[ACME-07]", "[ACME-1234567890]", "[A-1]",
                "[ABCDEFGHIJK-1]", "[ ACME-7]", "[ACME-7 ]", "[ACME 7]", "[ACME_7]", "[ACME-7a]", "[-7]",
                "[ACME-]", "[ACME-7-1]", "(ACME-7)", "{ACME-7}", "[ACME-7", "ACME-7]", "[]", "[\u00c4CME-7]",
                "[ACME-\uff17]")) {
            assertThat(IssueKeyInSubject.key(subject)).as(subject).isEmpty();
        }
    }

    @Test
    @DisplayName("S12-AC-16: the bounds of the key form: 2 and 10 characters of code, 1 to 9 digits")
    void s12Ac16_keyFormBounds() {
        assertThat(IssueKeyInSubject.key("[AB-1]")).contains(new IssueKey("AB", 1));
        assertThat(IssueKeyInSubject.key("[ABCDEFGHIJ-1]")).contains(new IssueKey("ABCDEFGHIJ", 1));
        assertThat(IssueKeyInSubject.key("[12-9]")).contains(new IssueKey("12", 9));
        assertThat(IssueKeyInSubject.key("[ACME-999999999]")).contains(new IssueKey("ACME", 999999999));
    }

    @Test
    @DisplayName("S12-AC-16: a letter outside ASCII whose upper case is an ASCII letter does not make a key")
    void s12Ac16_onlyAsciiLettersChangeCase() {
        // U+017F (long s) is S in upper case, U+0131 (dotless i) is I, U+212A (Kelvin) is K in lower case.
        assertThat(IssueKeyInSubject.key("[\u017fUPPORT-7]")).isEmpty();
        assertThat(IssueKeyInSubject.key("[\u0131T-7]")).isEmpty();
        assertThat(IssueKeyInSubject.key("[\u212aR-7]")).isEmpty();
    }
}
