package io.popdesk.helpdesk.attachment.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Which S3 setting keys are named as missing: for new files (S5B-AC-23) and for rows that point at S3
 * (S5B-AC-22). Written from the Javadoc of {@link S3KeyCheck} and from the two criteria. The values are
 * made up and carry no secret.
 */
class S3KeyCheckTest {

    private static final String ENDPOINT = "https://s3.example.test";
    private static final String REGION = "eu-central-1";
    private static final String BUCKET = "files";
    private static final String ACCESS = "test-access-value";
    private static final String SECRET = "test-secret-value";

    private static final String EITHER = "S3_REGION or S3_ENDPOINT";

    /** Every way the Javadoc says a value is missing: null, empty, or only white space. */
    private static final List<String> MISSING_VALUES = List.of("null", "", " ", "   ", "\t", "\n", " \t\r\n ");

    private static String value(String missing) {
        return "null".equals(missing) ? null : missing;
    }

    private static String label(String missing) {
        return "null".equals(missing)
                ? "null"
                : "\"" + missing.replace("\t", "\\t").replace("\r", "\\r").replace("\n", "\\n") + "\"";
    }

    // ---- nothing missing ----

    @Test
    @DisplayName("S5B-AC-23 with every key set, nothing is missing")
    void everyKeySetIsNothingMissing() {
        assertThat(S3KeyCheck.missing(ENDPOINT, REGION, BUCKET, ACCESS, SECRET, true)).isNotNull().isEmpty();
        assertThat(S3KeyCheck.missing(ENDPOINT, REGION, BUCKET, ACCESS, SECRET, false)).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("S5B-AC-23 a value with white space around it or inside it is not missing")
    void valueWithWhiteSpaceAroundItIsNotMissing() {
        assertThat(S3KeyCheck.missing(" e ", " r ", " b ", " a ", " s ", true)).isEmpty();
        assertThat(S3KeyCheck.missing(null, "\tr", "b\n", "a b", ".", true)).isEmpty();
        assertThat(S3KeyCheck.missing("\te", null, "b", "a", "s", false)).isEmpty();
    }

    // ---- one key missing ----

    static Stream<Arguments> missingValues() {
        return MISSING_VALUES.stream().map(v -> Arguments.of(label(v), value(v)));
    }

    @ParameterizedTest(name = "S5B-AC-23 S3_BUCKET as {0} is missing")
    @MethodSource("missingValues")
    @DisplayName("S5B-AC-23 a bucket that is null, empty, or white space is missing when new files go to S3")
    void bucketIsMissingWhenNeeded(String label, String bucket) {
        assertThat(S3KeyCheck.missing(ENDPOINT, REGION, bucket, ACCESS, SECRET, true)).containsExactly("S3_BUCKET");
    }

    @ParameterizedTest(name = "S5B-AC-22 S3_BUCKET as {0} is not asked for")
    @MethodSource("missingValues")
    @DisplayName("S5B-AC-22 the bucket is not asked for when S3 is only read by rows")
    void bucketIsNotAskedForWhenRowsNameIt(String label, String bucket) {
        assertThat(S3KeyCheck.missing(ENDPOINT, REGION, bucket, ACCESS, SECRET, false)).isEmpty();
    }

    @ParameterizedTest(name = "S5B-AC-23 S3_ACCESS_KEY as {0} is missing")
    @MethodSource("missingValues")
    @DisplayName("S5B-AC-23 an access key that is null, empty, or white space is missing, for new files and for rows")
    void accessKeyIsMissing(String label, String accessKey) {
        assertThat(S3KeyCheck.missing(ENDPOINT, REGION, BUCKET, accessKey, SECRET, true))
                .containsExactly("S3_ACCESS_KEY");
        assertThat(S3KeyCheck.missing(ENDPOINT, REGION, BUCKET, accessKey, SECRET, false))
                .containsExactly("S3_ACCESS_KEY");
    }

    @ParameterizedTest(name = "S5B-AC-23 S3_SECRET_KEY as {0} is missing")
    @MethodSource("missingValues")
    @DisplayName("S5B-AC-23 a secret key that is null, empty, or white space is missing, for new files and for rows")
    void secretKeyIsMissing(String label, String secretKey) {
        assertThat(S3KeyCheck.missing(ENDPOINT, REGION, BUCKET, ACCESS, secretKey, true))
                .containsExactly("S3_SECRET_KEY");
        assertThat(S3KeyCheck.missing(ENDPOINT, REGION, BUCKET, ACCESS, secretKey, false))
                .containsExactly("S3_SECRET_KEY");
    }

    // ---- the region and the endpoint ----

    @ParameterizedTest(name = "S5B-AC-23 S3_REGION as {0} with an endpoint is enough")
    @MethodSource("missingValues")
    @DisplayName("S5B-AC-23 the endpoint alone is enough")
    void endpointAloneIsEnough(String label, String region) {
        assertThat(S3KeyCheck.missing(ENDPOINT, region, BUCKET, ACCESS, SECRET, true)).isEmpty();
        assertThat(S3KeyCheck.missing(ENDPOINT, region, BUCKET, ACCESS, SECRET, false)).isEmpty();
    }

    @ParameterizedTest(name = "S5B-AC-23 S3_ENDPOINT as {0} with a region is enough")
    @MethodSource("missingValues")
    @DisplayName("S5B-AC-23 the region alone is enough")
    void regionAloneIsEnough(String label, String endpoint) {
        assertThat(S3KeyCheck.missing(endpoint, REGION, BUCKET, ACCESS, SECRET, true)).isEmpty();
        assertThat(S3KeyCheck.missing(endpoint, REGION, BUCKET, ACCESS, SECRET, false)).isEmpty();
    }

    static Stream<Arguments> pairsOfMissingValues() {
        return MISSING_VALUES.stream()
                .flatMap(e -> MISSING_VALUES.stream().map(r -> Arguments.of(label(e), label(r), value(e), value(r))));
    }

    @ParameterizedTest(name = "S5B-AC-23 S3_ENDPOINT as {0} and S3_REGION as {1} are missing as one name")
    @MethodSource("pairsOfMissingValues")
    @DisplayName("S5B-AC-23 with neither a region nor an endpoint, the two are named once, together")
    void neitherRegionNorEndpointIsOneName(String endpointLabel, String regionLabel, String endpoint, String region) {
        assertThat(S3KeyCheck.missing(endpoint, region, BUCKET, ACCESS, SECRET, true)).containsExactly(EITHER);
        assertThat(S3KeyCheck.missing(endpoint, region, BUCKET, ACCESS, SECRET, false)).containsExactly(EITHER);
    }

    @Test
    @DisplayName("S5B-AC-23 the name for the two keys is the constant, and it names both")
    void nameOfTheTwoKeysIsTheConstant() {
        assertThat(S3KeyCheck.REGION_OR_ENDPOINT).isEqualTo(EITHER).contains("S3_REGION", "S3_ENDPOINT");
    }

    // ---- every combination ----

    static Stream<Arguments> combinations() {
        List<Arguments> cases = new ArrayList<>();
        for (int bits = 0; bits < 64; bits++) {
            boolean endpoint = (bits & 1) != 0;
            boolean region = (bits & 2) != 0;
            boolean bucket = (bits & 4) != 0;
            boolean access = (bits & 8) != 0;
            boolean secret = (bits & 16) != 0;
            boolean bucketNeeded = (bits & 32) != 0;
            List<String> expected = new ArrayList<>();
            if (bucketNeeded && !bucket) {
                expected.add("S3_BUCKET");
            }
            if (!access) {
                expected.add("S3_ACCESS_KEY");
            }
            if (!secret) {
                expected.add("S3_SECRET_KEY");
            }
            if (!region && !endpoint) {
                expected.add(EITHER);
            }
            String label = "%s endpoint, %s region, %s bucket, %s access key, %s secret key, for %s"
                    .formatted(
                            word(endpoint), word(region), word(bucket), word(access), word(secret),
                            bucketNeeded ? "new files" : "rows");
            cases.add(Arguments.of(
                    label, endpoint, region, bucket, access, secret, bucketNeeded, List.copyOf(expected)));
        }
        return cases.stream();
    }

    private static String word(boolean set) {
        return set ? "an" : "no";
    }

    @ParameterizedTest(name = "S5B-AC-23 {0}: {7}")
    @MethodSource("combinations")
    @DisplayName("S5B-AC-23 the missing keys are named all at once, in the order bucket, access key, secret key, region or endpoint")
    void missingKeysAreNamedAllAtOnceInOrder(
            String label,
            boolean endpoint,
            boolean region,
            boolean bucket,
            boolean access,
            boolean secret,
            boolean bucketNeeded,
            List<String> expected) {
        for (String gone : List.of("null", "", " \t")) {
            List<String> missing = S3KeyCheck.missing(
                    endpoint ? ENDPOINT : value(gone),
                    region ? REGION : value(gone),
                    bucket ? BUCKET : value(gone),
                    access ? ACCESS : value(gone),
                    secret ? SECRET : value(gone),
                    bucketNeeded);

            assertThat(missing).as("missing values given as %s", label(gone)).containsExactlyElementsOf(expected);
        }
    }

    @Test
    @DisplayName("S5B-AC-23 with everything missing for new files, the four names come in their order")
    void everythingMissingForNewFiles() {
        assertThat(S3KeyCheck.missing(null, null, null, null, null, true))
                .containsExactly("S3_BUCKET", "S3_ACCESS_KEY", "S3_SECRET_KEY", "S3_REGION or S3_ENDPOINT");
        assertThat(S3KeyCheck.missing("", " ", "\t", "\n", "  ", true))
                .containsExactly("S3_BUCKET", "S3_ACCESS_KEY", "S3_SECRET_KEY", "S3_REGION or S3_ENDPOINT");
    }

    @Test
    @DisplayName("S5B-AC-22 with everything missing for rows, the three names come in their order, without the bucket")
    void everythingMissingForRows() {
        assertThat(S3KeyCheck.missing(null, null, null, null, null, false))
                .containsExactly("S3_ACCESS_KEY", "S3_SECRET_KEY", "S3_REGION or S3_ENDPOINT");
        assertThat(S3KeyCheck.missing("", " ", "\t", "\n", "  ", false))
                .containsExactly("S3_ACCESS_KEY", "S3_SECRET_KEY", "S3_REGION or S3_ENDPOINT");
    }

    // ---- what the answer is ----

    @ParameterizedTest(name = "S5B-AC-25 {0}: no value is in the answer")
    @MethodSource("combinations")
    @DisplayName("S5B-AC-25 the answer holds key names only, never a value it was given")
    void answerNeverHoldsAValue(
            String label,
            boolean endpoint,
            boolean region,
            boolean bucket,
            boolean access,
            boolean secret,
            boolean bucketNeeded,
            List<String> expected) {
        List<String> missing = S3KeyCheck.missing(
                endpoint ? ENDPOINT : " ",
                region ? REGION : " ",
                bucket ? BUCKET : " ",
                access ? ACCESS : " ",
                secret ? SECRET : " ",
                bucketNeeded);

        assertThat(missing)
                .isSubsetOf("S3_BUCKET", "S3_ACCESS_KEY", "S3_SECRET_KEY", EITHER)
                .doesNotHaveDuplicates()
                .noneMatch(name -> name.contains(ENDPOINT)
                        || name.contains(REGION)
                        || name.contains(BUCKET)
                        || name.contains(ACCESS)
                        || name.contains(SECRET));
    }

    @Test
    @DisplayName("S5B-AC-23 each call gives a new list, with something missing or with nothing")
    void eachCallGivesANewList() {
        List<String> none = S3KeyCheck.missing(ENDPOINT, REGION, BUCKET, ACCESS, SECRET, true);
        List<String> noneAgain = S3KeyCheck.missing(ENDPOINT, REGION, BUCKET, ACCESS, SECRET, true);
        List<String> some = S3KeyCheck.missing(null, null, null, null, null, true);
        List<String> someAgain = S3KeyCheck.missing(null, null, null, null, null, true);

        assertThat(none).isNotSameAs(noneAgain).isEqualTo(noneAgain);
        assertThat(some).isNotSameAs(someAgain).isEqualTo(someAgain);
    }
}
