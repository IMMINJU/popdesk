package io.popdesk.helpdesk.attachment.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Reading and writing the location of an {@code s3} row, {@code {bucket}/popdesk} (S5B-AC-02).
 * Written from the Javadoc of {@link BucketLocation} and from design 5b sections 0 and 8. Every
 * provider below gives the text of a location first.
 */
class BucketLocationTest {

    // ---- locations that are read ----

    static Stream<Arguments> readable() {
        return Stream.of(
                Arguments.of("files/popdesk", "files", "popdesk"),
                Arguments.of("my-bucket.example/popdesk", "my-bucket.example", "popdesk"),
                Arguments.of("b/p", "b", "p"),
                Arguments.of("bucket/other", "bucket", "other"),
                Arguments.of("bucket/a/b", "bucket", "a/b"),
                Arguments.of("bucket/a/b/c", "bucket", "a/b/c"),
                Arguments.of("bucket/a//b", "bucket", "a//b"),
                Arguments.of("popdesk/popdesk", "popdesk", "popdesk"),
                Arguments.of("Bucket/PopDesk", "Bucket", "PopDesk"),
                Arguments.of(" bucket/popdesk", " bucket", "popdesk"),
                Arguments.of("bucket /popdesk", "bucket ", "popdesk"),
                Arguments.of("bucket/ popdesk", "bucket", " popdesk"),
                Arguments.of("bucket/popdesk ", "bucket", "popdesk "),
                Arguments.of("bucket/popdesk\n", "bucket", "popdesk\n"),
                Arguments.of(" / ", " ", " "),
                Arguments.of("bucket/ /x", "bucket", " /x"),
                Arguments.of("bucket/x/ ", "bucket", "x/ "));
    }

    @ParameterizedTest(name = "S5B-AC-02 \"{0}\" is the bucket \"{1}\" and the prefix \"{2}\"")
    @MethodSource("readable")
    @DisplayName("S5B-AC-02 a location is cut at its first slash into the bucket and the prefix, untrimmed")
    void locationIsCutAtTheFirstSlash(String location, String bucket, String prefix) {
        Optional<BucketLocation> read = BucketLocation.parse(location);

        assertThat(read).isPresent();
        assertThat(read.get().bucket()).isEqualTo(bucket);
        assertThat(read.get().prefix()).isEqualTo(prefix);
    }

    @ParameterizedTest(name = "S5B-AC-02 \"{0}\" is written as it was read")
    @MethodSource("readable")
    @DisplayName("S5B-AC-02 a location that was read is written as the same text and read as the same value")
    void readLocationIsWrittenAsItWas(String location, String bucket, String prefix) {
        BucketLocation read = BucketLocation.parse(location).orElseThrow();

        assertThat(read.text()).isEqualTo(location);
        assertThat(BucketLocation.parse(read.text())).contains(read);
    }

    // ---- locations that are not ----

    static Stream<Arguments> unreadable() {
        return Stream.of(
                Arguments.of("no text at all", null),
                Arguments.of("an empty text", ""),
                Arguments.of("a bucket with no slash", "bucket"),
                Arguments.of("a space with no slash", " "),
                Arguments.of("the prefix with no bucket", "popdesk"),
                Arguments.of("a backslash where the slash goes", "bucket\\popdesk"),
                Arguments.of("a slash alone", "/"),
                Arguments.of("nothing before the slash", "/popdesk"),
                Arguments.of("nothing before the first of two slashes", "/bucket/popdesk"),
                Arguments.of("nothing after the slash", "bucket/"),
                Arguments.of("nothing after the slash of a one-letter bucket", "b/"),
                Arguments.of("two slashes alone", "//"),
                Arguments.of("three slashes alone", "///"),
                Arguments.of("a prefix that starts with a slash", "bucket//popdesk"),
                Arguments.of("a prefix that is a slash", "bucket//"),
                Arguments.of("a prefix that ends with a slash", "bucket/popdesk/"),
                Arguments.of("a longer prefix that ends with a slash", "bucket/a/b/"),
                Arguments.of("a prefix that starts and ends with a slash", "bucket//popdesk/"),
                Arguments.of("a prefix that ends with a slash after a space", "bucket/ /"),
                Arguments.of("a one-letter bucket and a prefix that is a slash", "b//"),
                Arguments.of("a one-letter bucket and a prefix that starts with a slash", "b//p"),
                Arguments.of("a one-letter bucket and a prefix that ends with a slash", "b/p/"));
    }

    @ParameterizedTest(name = "S5B-AC-02 {0} is no location")
    @MethodSource("unreadable")
    @DisplayName("S5B-AC-02 a text without a bucket, without a prefix, or with a slash at an end of the prefix is no location")
    void malformedTextIsNoLocation(String label, String location) {
        assertThat(BucketLocation.parse(location)).isNotNull().isEmpty();
    }

    // ---- writing ----

    @Test
    @DisplayName("S5B-AC-02 a location is written as the bucket, a slash, and the prefix")
    void locationIsWrittenAsBucketSlashPrefix() {
        assertThat(new BucketLocation("files", "popdesk").text()).isEqualTo("files/popdesk");
        assertThat(new BucketLocation("my-bucket.example", "a/b").text()).isEqualTo("my-bucket.example/a/b");
        assertThat(new BucketLocation(" b ", " p ").text()).isEqualTo(" b / p ");
    }

    @Test
    @DisplayName("S5B-AC-02 the location of new files, the bucket and popdesk, is read back as the same two")
    void bucketAndThePrefixOfNewFilesAreReadBack() {
        BucketLocation written = new BucketLocation("files", StoragePaths.PREFIX);

        assertThat(written.text()).isEqualTo("files/popdesk");
        assertThat(BucketLocation.parse(written.text())).contains(written);
    }

    @Test
    @DisplayName("S5B-AC-10 two buckets with the same prefix are two locations")
    void twoBucketsAreTwoLocations() {
        Optional<BucketLocation> one = BucketLocation.parse("bucket-a/popdesk");
        Optional<BucketLocation> other = BucketLocation.parse("bucket-b/popdesk");

        assertThat(one).isPresent().isNotEqualTo(other);
        assertThat(one).isEqualTo(BucketLocation.parse("bucket-a/popdesk"));
    }
}
