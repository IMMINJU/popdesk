package io.popdesk.helpdesk.attachment.storage;

import java.io.InputStream;
import java.time.Duration;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Supplier;
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkServiceException;
import software.amazon.awssdk.core.sync.RequestBody;

/**
 * {@link AttachmentStorage} in a bucket of S3 or an S3-compatible server ({@code POPDESK_STORAGE=s3}):
 * the contents of an attachment are the object {@code {prefix}/{ID}} ({@link StoragePaths}), and the
 * location recorded on a row is {@code {bucket}/{prefix}} ({@link BucketLocation}).
 *
 * <p>A file is written with one {@code PutObject} and no retry, read with a range request per piece,
 * removed with {@code DeleteObject}, and looked at with {@code HeadObject} (design 5b section 0, 7).
 * popdesk always serves the bytes itself: it never hands out the bucket's address or a signed URL
 * (S5B-AC-06, TM-54).
 *
 * <p>What the SDK throws stays in here. Outside goes a {@link StorageException} whose message has the
 * status code and the error code, or the class of the SDK's exception, and nothing of its message:
 * that can quote the address, the bucket, and request headers (S5B-AC-25). "Not there" is told by
 * status 404 alone; the error code of a HEAD cannot be trusted.
 */
final class S3AttachmentStorage implements AttachmentStorage {

    static final String KIND = "s3";

    /** How long a request of the check at start may take, retries included (design 5b section 2). */
    private static final Duration AT_START = Duration.ofSeconds(10);

    private static final String MARKER_TEXT = "popdesk keeps attachment files under this prefix. Do not remove "
            + "this object: without it popdesk does not start while attachments are stored here "
            + "(docs/operate/configuration.md).\n";

    private static final int NOT_FOUND = 404;

    private final S3Access access;
    private final BucketLocation where;

    S3AttachmentStorage(S3Access access, BucketLocation where) {
        this.access = access;
        this.where = where;
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public String location() {
        return where.text();
    }

    @Override
    public void write(UUID attachmentId, InputStream contents, long size) {
        String key = StoragePaths.objectKey(where.prefix(), attachmentId);
        call(() -> access.writer.putObject(b -> b.bucket(where.bucket()).key(key).contentLength(size),
                RequestBody.fromInputStream(contents, size)));
    }

    @Override
    public byte[] read(UUID attachmentId, long offset, int length) {
        String key = StoragePaths.objectKey(where.prefix(), attachmentId);
        String range = "bytes=" + offset + "-" + (offset + length - 1);
        try {
            return access.client.getObjectAsBytes(b -> b.bucket(where.bucket()).key(key).range(range))
                    .asByteArrayUnsafe();
        } catch (SdkServiceException e) {
            if (e.statusCode() == NOT_FOUND) {
                throw new StorageFileMissingException("The s3 storage has no object for the attachment");
            }
            throw answered(e);
        } catch (SdkClientException e) {
            throw unreachable(e);
        }
    }

    @Override
    public void delete(UUID attachmentId) {
        String key = StoragePaths.objectKey(where.prefix(), attachmentId);
        call(() -> access.client.deleteObject(b -> b.bucket(where.bucket()).key(key)));
    }

    @Override
    public OptionalLong size(UUID attachmentId) {
        String key = StoragePaths.objectKey(where.prefix(), attachmentId);
        try {
            return OptionalLong.of(access.client.headObject(b -> b.bucket(where.bucket()).key(key)).contentLength());
        } catch (SdkServiceException e) {
            if (e.statusCode() == NOT_FOUND) {
                return OptionalLong.empty();
            }
            throw answered(e);
        } catch (SdkClientException e) {
            throw unreachable(e);
        }
    }

    @Override
    public boolean hasMarker() {
        try {
            access.client.headObject(b -> b.bucket(where.bucket()).key(markerKey()).overrideConfiguration(atStart()));
            return true;
        } catch (SdkServiceException e) {
            if (e.statusCode() == NOT_FOUND) {
                return false;
            }
            throw answered(e);
        } catch (SdkClientException e) {
            throw unreachable(e);
        }
    }

    @Override
    public void writeMarker() {
        call(() -> access.client.putObject(
                b -> b.bucket(where.bucket()).key(markerKey()).overrideConfiguration(atStart()),
                RequestBody.fromString(MARKER_TEXT)));
    }

    @Override
    public void checkWritable() {
        String key = where.prefix() + "/" + StoragePaths.WRITE_CHECK;
        call(() -> access.client.putObject(b -> b.bucket(where.bucket()).key(key).overrideConfiguration(atStart()),
                RequestBody.fromString("popdesk\n")));
        call(() -> access.client.deleteObject(
                b -> b.bucket(where.bucket()).key(key).overrideConfiguration(atStart())));
    }

    private String markerKey() {
        return where.prefix() + "/" + StoragePaths.MARKER;
    }

    private static AwsRequestOverrideConfiguration atStart() {
        return AwsRequestOverrideConfiguration.builder().apiCallTimeout(AT_START).build();
    }

    /** A request whose every answer but success is a failure. */
    private static void call(Supplier<?> request) {
        try {
            request.get();
        } catch (SdkServiceException e) {
            throw answered(e);
        } catch (SdkClientException e) {
            throw unreachable(e);
        }
    }

    /** The storage answered with an error: the status and S3's error code, such as {@code 403 AccessDenied}. */
    private static StorageException answered(SdkServiceException e) {
        String code = e instanceof AwsServiceException aws && aws.awsErrorDetails() != null
                ? aws.awsErrorDetails().errorCode() : null;
        // The code comes from the server; only what an S3 error code is made of is kept.
        String kept = code == null ? "" : code.replaceAll("[^A-Za-z0-9.]", "");
        return new StorageException("The s3 storage answered with status " + e.statusCode()
                + (kept.isEmpty() ? "" : " " + kept.substring(0, Math.min(kept.length(), 60))));
    }

    /** No answer came: the connection failed or was cut, or the time limit passed. */
    private static StorageUnreachableException unreachable(SdkClientException e) {
        return new StorageUnreachableException("The s3 storage could not be reached ("
                + e.getClass().getSimpleName() + ")");
    }
}
