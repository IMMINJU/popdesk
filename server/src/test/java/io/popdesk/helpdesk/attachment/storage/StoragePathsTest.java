package io.popdesk.helpdesk.attachment.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The path of an attachment's file in a folder and the key of its object in a bucket (S5B-AC-03).
 * Written from the Javadoc of {@link StoragePaths} and from design 5b sections 0 and 8. The expected
 * values are written out, not built the way the class builds them.
 */
class StoragePathsTest {

    // ---- the file in a folder ----

    @ParameterizedTest(name = "S5B-AC-03 the file of {0} is {1}")
    @CsvSource({
        "3f2b6c1e-0000-4000-8000-000000000001, 3f/3f2b6c1e-0000-4000-8000-000000000001",
        "00000000-0000-0000-0000-000000000000, 00/00000000-0000-0000-0000-000000000000",
        "ffffffff-ffff-ffff-ffff-ffffffffffff, ff/ffffffff-ffff-ffff-ffff-ffffffffffff",
        "a1b2c3d4-e5f6-4789-abcd-ef0123456789, a1/a1b2c3d4-e5f6-4789-abcd-ef0123456789",
        "0a9e8d7c-6b5a-4f3e-9d2c-1b0a99887766, 0a/0a9e8d7c-6b5a-4f3e-9d2c-1b0a99887766",
        "90000000-0000-4000-8000-0000000000ab, 90/90000000-0000-4000-8000-0000000000ab"
    })
    @DisplayName("S5B-AC-03 a file is at the first two characters of its ID, a slash, and the ID")
    void fileIsUnderTheFirstTwoCharactersOfTheId(String id, String path) {
        assertThat(StoragePaths.filePath(UUID.fromString(id))).isEqualTo(path);
    }

    @Test
    @DisplayName("S5B-AC-03 the path is in lowercase whatever case the ID was read from")
    void filePathIsLowercase() {
        UUID id = UUID.fromString("AB12CDEF-0000-4000-8000-00000000F00D");

        assertThat(StoragePaths.filePath(id)).isEqualTo("ab/ab12cdef-0000-4000-8000-00000000f00d");
    }

    static Stream<Arguments> manyIds() {
        return Stream.of(
                        new UUID(0L, 0L),
                        new UUID(-1L, -1L),
                        new UUID(Long.MIN_VALUE, Long.MAX_VALUE),
                        new UUID(0x0123456789ABCDEFL, 0xFEDCBA9876543210L),
                        new UUID(0x1000000000000000L, 1L),
                        new UUID(0x0F00000000000000L, 2L),
                        new UUID(0xF0F0F0F0F0F0F0F0L, 0x0F0F0F0F0F0F0F0FL),
                        UUID.fromString("3f2b6c1e-0000-4000-8000-000000000001"))
                .map(Arguments::of);
    }

    @ParameterizedTest(name = "S5B-AC-03 the path of {0}")
    @MethodSource("manyIds")
    @DisplayName("S5B-AC-03 a path is 39 characters: a folder of two, one slash, and the 36 of the ID")
    void filePathIsThirtyNineCharacters(UUID id) {
        String path = StoragePaths.filePath(id);

        assertThat(path).hasSize(39).matches("[0-9a-f]{2}/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        assertThat(path.substring(3)).isEqualTo(id.toString());
        assertThat(path.substring(0, 2)).isEqualTo(id.toString().substring(0, 2));
        assertThat(path.indexOf('/')).isEqualTo(2).isEqualTo(path.lastIndexOf('/'));
    }

    @Test
    @DisplayName("S5B-AC-03 two IDs that start alike share a folder and differ in the file")
    void idsThatStartAlikeShareAFolder() {
        String one = StoragePaths.filePath(UUID.fromString("3f2b6c1e-0000-4000-8000-000000000001"));
        String other = StoragePaths.filePath(UUID.fromString("3f999999-0000-4000-8000-000000000002"));
        String elsewhere = StoragePaths.filePath(UUID.fromString("f32b6c1e-0000-4000-8000-000000000001"));

        assertThat(one).startsWith("3f/").isNotEqualTo(other);
        assertThat(other).startsWith("3f/");
        assertThat(elsewhere).startsWith("f3/");
    }

    @Test
    @DisplayName("S5B-AC-03 no ID is a NullPointerException for the file and for its temporary name")
    void nullIdIsANullPointerException() {
        assertThatThrownBy(() -> StoragePaths.filePath(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> StoragePaths.partPath(null)).isInstanceOf(NullPointerException.class);
    }

    // ---- the temporary name ----

    @ParameterizedTest(name = "S5B-AC-03 the temporary name of {0} is {1}")
    @CsvSource({
        "3f2b6c1e-0000-4000-8000-000000000001, 3f/3f2b6c1e-0000-4000-8000-000000000001.part",
        "00000000-0000-0000-0000-000000000000, 00/00000000-0000-0000-0000-000000000000.part",
        "ffffffff-ffff-ffff-ffff-ffffffffffff, ff/ffffffff-ffff-ffff-ffff-ffffffffffff.part",
        "a1b2c3d4-e5f6-4789-abcd-ef0123456789, a1/a1b2c3d4-e5f6-4789-abcd-ef0123456789.part"
    })
    @DisplayName("S5B-AC-03 the temporary name is the ID with .part at its end, in the folder of the file")
    void temporaryNameIsTheIdWithPart(String id, String path) {
        assertThat(StoragePaths.partPath(UUID.fromString(id))).isEqualTo(path);
    }

    @ParameterizedTest(name = "S5B-AC-03 the temporary name of {0}")
    @MethodSource("manyIds")
    @DisplayName("S5B-AC-03 the temporary name is the path of the file and .part, and is not that path")
    void temporaryNameIsTheFilePathAndPart(UUID id) {
        String file = StoragePaths.filePath(id);
        String part = StoragePaths.partPath(id);

        assertThat(part).isNotNull().isNotEqualTo(file).hasSize(44).startsWith(file).endsWith(".part");
        assertThat(part.substring(0, part.lastIndexOf('/'))).isEqualTo(file.substring(0, file.lastIndexOf('/')));
    }

    // ---- the object in a bucket ----

    @ParameterizedTest(name = "S5B-AC-03 the key of {0} is {1}")
    @CsvSource({
        "3f2b6c1e-0000-4000-8000-000000000001, popdesk/3f2b6c1e-0000-4000-8000-000000000001",
        "00000000-0000-0000-0000-000000000000, popdesk/00000000-0000-0000-0000-000000000000",
        "ffffffff-ffff-ffff-ffff-ffffffffffff, popdesk/ffffffff-ffff-ffff-ffff-ffffffffffff",
        "A1B2C3D4-E5F6-4789-ABCD-EF0123456789, popdesk/a1b2c3d4-e5f6-4789-abcd-ef0123456789"
    })
    @DisplayName("S5B-AC-03 a key is popdesk, a slash, and the ID in lowercase, with no folder of two")
    void keyIsThePrefixAndTheId(String id, String key) {
        assertThat(StoragePaths.objectKey("popdesk", UUID.fromString(id))).isEqualTo(key);
        assertThat(StoragePaths.objectKey(StoragePaths.PREFIX, UUID.fromString(id))).isEqualTo(key);
    }

    @ParameterizedTest(name = "S5B-AC-03 the prefix \"{0}\" gives {1}")
    @CsvSource(
            delimiter = '|',
            ignoreLeadingAndTrailingWhitespace = false,
            value = {
                "other|other/3f2b6c1e-0000-4000-8000-000000000001",
                "a/b|a/b/3f2b6c1e-0000-4000-8000-000000000001",
                "POPDESK|POPDESK/3f2b6c1e-0000-4000-8000-000000000001",
                " pop desk |' pop desk /3f2b6c1e-0000-4000-8000-000000000001'",
                "/lead|/lead/3f2b6c1e-0000-4000-8000-000000000001",
                "trail/|trail//3f2b6c1e-0000-4000-8000-000000000001",
                "''|/3f2b6c1e-0000-4000-8000-000000000001"
            })
    @DisplayName("S5B-AC-03 the prefix goes into the key as it is, unchecked")
    void prefixIsTakenAsItIs(String prefix, String key) {
        UUID id = UUID.fromString("3f2b6c1e-0000-4000-8000-000000000001");

        assertThat(StoragePaths.objectKey(prefix, id)).isEqualTo(key);
    }

    @Test
    @DisplayName("S5B-AC-03 no prefix gives a key that starts with null/")
    void nullPrefixGivesAKeyThatStartsWithNull() {
        UUID id = UUID.fromString("3f2b6c1e-0000-4000-8000-000000000001");

        assertThat(StoragePaths.objectKey(null, id)).isEqualTo("null/3f2b6c1e-0000-4000-8000-000000000001");
    }

    @Test
    @DisplayName("S5B-AC-03 no ID is a NullPointerException for the key, with a prefix or without")
    void nullIdIsANullPointerExceptionForTheKey() {
        assertThatThrownBy(() -> StoragePaths.objectKey("popdesk", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> StoragePaths.objectKey(null, null)).isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest(name = "S5B-AC-03 the key of {0}")
    @MethodSource("manyIds")
    @DisplayName("S5B-AC-03 a key under popdesk is 44 characters and ends with the ID")
    void keyEndsWithTheId(UUID id) {
        String key = StoragePaths.objectKey("popdesk", id);

        assertThat(key).hasSize(44).startsWith("popdesk/").endsWith("/" + id);
        assertThat(key.indexOf('/')).isEqualTo(7).isEqualTo(key.lastIndexOf('/'));
    }

    // ---- the names ----

    @Test
    @DisplayName("S5B-AC-20 the marker is .popdesk-storage and the prefix of every key is popdesk")
    void markerAndPrefixAreTheNamesOfTheDesign() {
        assertThat(StoragePaths.MARKER).isEqualTo(".popdesk-storage");
        assertThat(StoragePaths.PREFIX).isEqualTo("popdesk");
    }

    @Test
    @DisplayName("S5B-AC-24 what a start writes to try the storage starts with .popdesk- and is not the marker")
    void writeCheckStartsWithPopdeskAndIsNotTheMarker() {
        assertThat(StoragePaths.WRITE_CHECK)
                .startsWith(".popdesk-")
                .isNotEqualTo(StoragePaths.MARKER)
                .doesNotContain("/")
                .doesNotMatch("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}.*");
    }
}
