package io.popdesk.helpdesk.attachment.storage;

import java.util.ArrayList;
import java.util.List;

/**
 * Which S3 setting keys are missing for what popdesk is about to do with S3 (S5B-AC-22, S5B-AC-23).
 * It works on the values and returns key names only, so its answer can go into a log. Pure.
 */
final class S3KeyCheck {

    /** What stands for the two keys of which one is enough. */
    static final String REGION_OR_ENDPOINT = "S3_REGION or S3_ENDPOINT";

    private S3KeyCheck() {}

    /**
     * The names of the keys that are missing, in this order: {@code S3_BUCKET} (only when
     * {@code bucketNeeded}), {@code S3_ACCESS_KEY}, {@code S3_SECRET_KEY}, then
     * {@value #REGION_OR_ENDPOINT} when both of these two are missing; one of them is enough.
     *
     * <p>A value is missing when it is null, empty, or only white space.
     *
     * @param endpoint the value of {@code S3_ENDPOINT}
     * @param region the value of {@code S3_REGION}
     * @param bucket the value of {@code S3_BUCKET}
     * @param accessKey the value of {@code S3_ACCESS_KEY}
     * @param secretKey the value of {@code S3_SECRET_KEY}
     * @param bucketNeeded true when new files go to S3; false when S3 is only read and removed from by
     *     rows, which name their bucket themselves
     * @return the names, empty when nothing is missing; a new list, never null, and never a value
     */
    public static List<String> missing(String endpoint, String region, String bucket, String accessKey,
            String secretKey, boolean bucketNeeded) {
        List<String> missing = new ArrayList<>();
        if (bucketNeeded && blank(bucket)) {
            missing.add("S3_BUCKET");
        }
        if (blank(accessKey)) {
            missing.add("S3_ACCESS_KEY");
        }
        if (blank(secretKey)) {
            missing.add("S3_SECRET_KEY");
        }
        if (blank(region) && blank(endpoint)) {
            missing.add(REGION_OR_ENDPOINT);
        }
        return missing;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
