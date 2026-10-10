package io.popdesk;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Which {@code POPDESK_LOCALE} values popdesk takes (verify.md V-6): {@code en} and {@code ko}, in any
 * case and with spaces around them. What an app started with another value does is in the
 * {@code start} package.
 */
class InstallationLocaleTest {

    @ParameterizedTest(name = "V-6 POPDESK_LOCALE=\"{0}\" is {1}")
    @CsvSource(value = {"en|en", "ko|ko", "KO|ko", "EN|en", "Ko|ko", " ko|ko", "ko |ko", "\tEN \t|en"},
            delimiter = '|', ignoreLeadingAndTrailingWhitespace = false)
    @DisplayName("V-6 en and ko are taken whatever their case and the spaces around them")
    void caseAndSpacesDoNotMatter(String configured, String language) {
        assertThat(InstallationLocale.parse(configured)).contains(language);
    }

    @Test
    @DisplayName("V-6 without POPDESK_LOCALE the language is en")
    void missingKeyIsEnglish() {
        assertThat(InstallationLocale.parse(null)).contains("en");
    }

    @ParameterizedTest(name = "V-6 POPDESK_LOCALE=\"{0}\" is not taken")
    @ValueSource(strings = {"", " ", "ko-KR", "ko_KR", "en-US", "k o", "kor", "ja", "korean", "en,ko"})
    @DisplayName("V-6 an empty value and any other value are not taken")
    void otherValuesAreRefused(String configured) {
        assertThat(InstallationLocale.parse(configured)).isEmpty();
    }
}
