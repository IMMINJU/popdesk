package io.popdesk.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.deploy.AppSteps.Person;
import io.popdesk.deploy.Transfer.Outcome;
import io.popdesk.support.Wait;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import tools.jackson.databind.JsonNode;

/**
 * 10MB attachments in an {@code s3} storage, sixteen uploads at once and then sixteen downloads at
 * once (all the transfers the app lets run together), on the real image with the 384MB heap of
 * {@link AttachmentLoadTest}. The S3 stand-in runs on the installation's network
 * ({@code storage-s3.compose.yml}).
 */
@Tag("deploy")
class S3AttachmentLoadTest {

    private static final int AT_ONCE = 16;

    private Stack stack;

    @AfterEach
    void remove() {
        if (stack != null) {
            stack.close();
        }
    }

    @Test
    @DisplayName("S5B-AC-53 with -Xmx384m and POPDESK_STORAGE=s3, 16 uploads of 10MB at once and then 16 downloads "
            + "at once succeed with the same hashes; the files are objects in the bucket, the app did not restart, "
            + "and its log has no OutOfMemoryError")
    void sixteenUploadsAndDownloadsAtOnce() throws Exception {
        String accessKey = "pdload" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
        String secretKey = "pdload" + UUID.randomUUID().toString().replace("-", "");
        String bucket = "pd-load-" + UUID.randomUUID().toString().substring(0, 8);
        stack = Stack.release("s3load").withResource("storage-s3.compose.yml")
                .env("POPDESK_TEST_S3_ACCESS", accessKey).env("POPDESK_TEST_S3_SECRET", secretKey);
        stack.up("db", "s3");
        try (S3Client s3 = S3Client.builder()
                .endpointOverride(URI.create("http://127.0.0.1:" + stack.port("s3", 7070)))
                .region(Region.US_EAST_1).forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .httpClient(UrlConnectionHttpClient.create()).build()) {
            Wait.until("the S3 stand-in takes requests and has the bucket", Duration.ofMinutes(1), () -> {
                try {
                    s3.createBucket(b -> b.bucket(bucket));
                    return bucket;
                } catch (SdkException notYet) {
                    return null;
                }
            });
            stack.appEnv("JDK_JAVA_OPTIONS", "-Xmx384m")
                    .appEnv("POPDESK_STORAGE", "s3")
                    .appEnv("S3_ENDPOINT", "http://s3:7070")
                    .appEnv("S3_BUCKET", bucket)
                    .appEnv("S3_ACCESS_KEY", accessKey)
                    .appEnv("S3_SECRET_KEY", secretKey);
            stack.up();
            stack.awaitHealthy("app", Duration.ofMinutes(2));

            AppSteps steps = new AppSteps(stack);
            Person admin = steps.signInAdmin();
            String company = steps.createCompany(admin);
            List<Person> customers = new ArrayList<>();
            List<String> issues = new ArrayList<>();
            for (int i = 0; i < AT_ONCE / 2; i++) {
                Person customer = steps.join(admin, "CUSTOMER", List.of(company), null);
                customers.add(customer);
                issues.add(steps.fileIssue(customer, company));
            }

            // Two per customer, each to their own issue (the public limit is 50MB per issue).
            List<Callable<Outcome>> uploads = new ArrayList<>();
            for (int i = 0; i < AT_ONCE; i++) {
                Person who = customers.get(i % customers.size());
                String issue = issues.get(i % customers.size());
                long seed = 5300 + i;
                uploads.add(() -> Transfer.upload(who, issue, seed, Transfer.TEN_MB));
            }
            List<Outcome> uploaded = allAtOnce(uploads);
            for (Outcome upload : uploaded) {
                assertThat(upload.status()).as("%s", upload).isEqualTo(201);
            }

            List<Callable<Outcome>> downloads = new ArrayList<>();
            for (int i = 0; i < AT_ONCE; i++) {
                Person who = customers.get(i % customers.size());
                String issue = issues.get(i % customers.size());
                String id = uploaded.get(i).attachmentId();
                downloads.add(() -> Transfer.download(who, issue, id));
            }
            List<Outcome> downloaded = allAtOnce(downloads);
            for (int i = 0; i < AT_ONCE; i++) {
                assertThat(downloaded.get(i).status()).as("%s", downloaded.get(i)).isEqualTo(200);
                assertThat(downloaded.get(i).sha256()).as("hash of %s", downloaded.get(i))
                        .isEqualTo(Transfer.sha256(5300 + i, Transfer.TEN_MB));
            }

            List<String> keys = s3.listObjectsV2Paginator(b -> b.bucket(bucket)).contents().stream()
                    .filter(o -> o.size() == Transfer.TEN_MB).map(o -> o.key()).toList();
            assertThat(keys).as("10MB objects in the bucket")
                    .containsExactlyInAnyOrderElementsOf(uploaded.stream().map(u -> "popdesk/" + u.attachmentId())
                            .toList());
        }

        JsonNode state = stack.inspect("app").path("State");
        assertThat(state.path("OOMKilled").asBoolean(true)).as("app OOMKilled, state %s", state).isFalse();
        assertThat(state.path("Running").asBoolean(false)).as("app running, state %s", state).isTrue();
        assertThat(stack.inspect("app").path("RestartCount").asInt(-1)).as("app restart count").isZero();
        assertThat(stack.logs("app")).as("app log").doesNotContain("OutOfMemoryError");
    }

    /** Runs the tasks in their own threads, released together, and returns the outcomes in order. */
    private static List<Outcome> allAtOnce(List<Callable<Outcome>> tasks) throws InterruptedException {
        CountDownLatch go = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(tasks.size())) {
            List<Future<Outcome>> futures = new ArrayList<>();
            for (Callable<Outcome> task : tasks) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return task.call();
                }));
            }
            go.countDown();
            List<Outcome> out = new ArrayList<>();
            for (Future<Outcome> f : futures) {
                try {
                    out.add(f.get());
                } catch (ExecutionException e) {
                    throw new AssertionError("A transfer broke: " + e.getCause(), e.getCause());
                }
            }
            return out;
        }
    }
}
