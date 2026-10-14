package io.popdesk.helpdesk.attachment.storage;

import java.util.Optional;

/**
 * The {@code storage_location} of an {@code s3} row: {@code {bucket}/{prefix}}, cut at the first
 * {@code /} (a bucket name has none). The address of the storage is not part of it: the same bucket
 * reached at another address still holds the file (acceptance 5b section 5, 5). Pure.
 *
 * @param bucket the bucket's name, not empty, without {@code /}
 * @param prefix what every key in the bucket starts with, before a {@code /}; not empty, and neither
 *     starting nor ending with {@code /}
 */
record BucketLocation(String bucket, String prefix) {

    /**
     * Reads a row's location.
     *
     * @param location the text of {@code storage_location}; may be null
     * @return the bucket and the prefix; empty when {@code location} is null, has no {@code /}, has
     *     nothing before the first {@code /} or nothing after it, or when what follows the first
     *     {@code /} starts or ends with {@code /}. Nothing is trimmed: a space is part of a name.
     */
    public static Optional<BucketLocation> parse(String location) {
        if (location == null) {
            return Optional.empty();
        }
        int cut = location.indexOf('/');
        if (cut <= 0 || cut == location.length() - 1) {
            return Optional.empty();
        }
        String prefix = location.substring(cut + 1);
        if (prefix.startsWith("/") || prefix.endsWith("/")) {
            return Optional.empty();
        }
        return Optional.of(new BucketLocation(location.substring(0, cut), prefix));
    }

    /**
     * The location as a row holds it: {@code {bucket}/{prefix}}. {@link #parse} of it gives this value
     * again when this value came from {@link #parse}.
     *
     * @return the text, never null
     */
    public String text() {
        return bucket + "/" + prefix;
    }
}
