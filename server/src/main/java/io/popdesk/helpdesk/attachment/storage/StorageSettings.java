package io.popdesk.helpdesk.attachment.storage;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * The storage settings of configuration.md as the app and the {@code popdesk} command read them:
 * {@code POPDESK_STORAGE}, {@code POPDESK_STORAGE_DIR}, and the five {@code S3_} keys. A value that is
 * empty or only white space counts as not set. {@code S3_SECRET_KEY_FILE} is read by whoever gives the
 * lookup: {@code SecretFiles} in the app, the command for itself.
 *
 * <p>Nothing here prints a value: {@link #problems} and {@link #missingS3Keys} name keys, and
 * {@link #toString} leaves the two S3 keys out (S5B-AC-25).
 */
record StorageSettings(String kind, String folder, String endpoint, String region, String bucket, String accessKey,
        String secretKey) {

    /** The default of {@code POPDESK_STORAGE_DIR}, the attachment volume of the image. */
    static final String DEFAULT_FOLDER = "/data/attachments";

    /** Reads the keys with {@code lookup}, which gives null for a key that is not set. */
    static StorageSettings read(UnaryOperator<String> lookup) {
        String kind = value(lookup, "POPDESK_STORAGE");
        String folder = value(lookup, "POPDESK_STORAGE_DIR");
        return new StorageSettings(kind == null ? DatabaseAttachmentStorage.KIND : kind,
                folder == null ? DEFAULT_FOLDER : folder, value(lookup, "S3_ENDPOINT"), value(lookup, "S3_REGION"),
                value(lookup, "S3_BUCKET"), value(lookup, "S3_ACCESS_KEY"), value(lookup, "S3_SECRET_KEY"));
    }

    private static String value(UnaryOperator<String> lookup, String key) {
        String value = lookup.apply(key);
        return value == null || value.isBlank() ? null : value.strip();
    }

    /**
     * What is wrong with the shape of the settings for the storage of new files (S5B-AC-23), one
     * sentence each that names the key; empty when they can be used.
     */
    List<String> problems() {
        List<String> problems = new ArrayList<>();
        switch (kind) {
            case DatabaseAttachmentStorage.KIND -> { }
            case DiskAttachmentStorage.KIND -> {
                if (folderPath() == null) {
                    problems.add("POPDESK_STORAGE_DIR must be an absolute path when POPDESK_STORAGE is disk.");
                }
            }
            case S3AttachmentStorage.KIND -> {
                List<String> missing = missingS3Keys(true);
                if (!missing.isEmpty()) {
                    problems.add("POPDESK_STORAGE is s3 and these settings are missing: " + String.join(", ", missing)
                            + ".");
                }
                if (bucket != null && bucket.contains("/")) {
                    problems.add("S3_BUCKET must be the name of a bucket, without a path.");
                }
                if (endpoint != null && endpointAddress() == null) {
                    problems.add("S3_ENDPOINT must be an http or https address.");
                }
            }
            default -> problems.add("POPDESK_STORAGE must be db, disk, or s3.");
        }
        return problems;
    }

    /** The folder of {@code disk} without {@code .} and {@code ..} parts, or null when it is not an absolute path. */
    Path folderPath() {
        try {
            Path path = Path.of(folder);
            return path.isAbsolute() ? path.normalize() : null;
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /** The S3 keys that are missing, by name ({@link S3KeyCheck#missing}). */
    List<String> missingS3Keys(boolean bucketNeeded) {
        return S3KeyCheck.missing(endpoint, region, bucket, accessKey, secretKey, bucketNeeded);
    }

    /** {@code S3_ENDPOINT} as an address, or null when it is not set or not an http or https address with a host. */
    URI endpointAddress() {
        if (endpoint == null) {
            return null;
        }
        try {
            URI address = new URI(endpoint);
            boolean web = "http".equalsIgnoreCase(address.getScheme()) || "https".equalsIgnoreCase(address.getScheme());
            return web && address.getHost() != null ? address : null;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /** Whether {@code S3_ENDPOINT} is an {@code http} address: requests and files travel unencrypted (S5B-AC-26). */
    boolean plainEndpoint() {
        URI address = endpointAddress();
        return address != null && "http".equalsIgnoreCase(address.getScheme());
    }

    @Override
    public String toString() {
        return "StorageSettings[kind=" + kind + "]";
    }
}
