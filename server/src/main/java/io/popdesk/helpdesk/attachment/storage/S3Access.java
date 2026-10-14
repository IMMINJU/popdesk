package io.popdesk.helpdesk.attachment.storage;

import java.net.URI;
import java.time.Duration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.DefaultRetryStrategy;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

/**
 * The S3 clients of an installation (ADR D-60), made from the settings and from nothing else: the
 * credentials are {@code S3_ACCESS_KEY} and {@code S3_SECRET_KEY} only, never the SDK's own chain, so no
 * {@code AWS_} variable becomes a setting. With {@code S3_ENDPOINT} the requests go to that address in
 * path style ({@code address/bucket/key}), signed for {@code S3_REGION} or, without one,
 * {@code us-east-1}; without {@code S3_ENDPOINT} they go to AWS in its own style.
 *
 * <p>Every request has a time limit: 5 seconds to connect, 60 seconds for an attempt (S5B-AC-34).
 * Checksums are sent and checked only where S3 requires them; older S3-compatible servers do not take
 * the SDK's default trailer.
 */
final class S3Access implements AutoCloseable {

    static final Duration CONNECT = Duration.ofSeconds(5);
    static final Duration ATTEMPT = Duration.ofSeconds(60);

    private static final String DEFAULT_REGION = "us-east-1";

    /** Reads, removals, and checks, with the SDK's default retries. */
    final S3Client client;

    /** Writes of file contents: one attempt, because the stream that was received cannot be read twice. */
    final S3Client writer;

    S3Access(StorageSettings settings) {
        this.client = builder(settings).build();
        this.writer = builder(settings)
                .overrideConfiguration(o -> o.apiCallAttemptTimeout(ATTEMPT)
                        .retryStrategy(DefaultRetryStrategy.doNotRetry()))
                .build();
    }

    private static S3ClientBuilder builder(StorageSettings settings) {
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(settings.region() == null ? DEFAULT_REGION : settings.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(settings.accessKey(), settings.secretKey())))
                .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(CONNECT).socketTimeout(ATTEMPT))
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .overrideConfiguration(o -> o.apiCallAttemptTimeout(ATTEMPT));
        URI endpoint = settings.endpointAddress();
        if (endpoint != null) {
            builder.endpointOverride(endpoint).forcePathStyle(true);
        }
        return builder;
    }

    @Override
    public void close() {
        client.close();
        writer.close();
    }
}
