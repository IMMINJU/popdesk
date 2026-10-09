package io.popdesk.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.deploy.AppSteps.Person;
import io.popdesk.deploy.Metrics.Sample;
import io.popdesk.deploy.Transfer.Outcome;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * 10MB attachments, ten uploads and ten downloads at once, on the real image with a 384MB heap
 * (architecture.md "첨부 저장", first-slice premortem section 1).
 *
 * <p>The heap is set with {@code JDK_JAVA_OPTIONS=-Xmx384m}. Nothing else about the JVM changes, so
 * the garbage collector is whatever the image chooses. The test starts transfers right after the
 * app is up: the first ten uploads are the first runs of the attachment statements on their
 * connections, then ten more uploads run together with ten downloads of the first ten files.
 */
@Tag("deploy")
class AttachmentLoadTest {

    private static final long MAX_HEAP = 384L * 1024 * 1024;

    @Test
    @DisplayName("S0-AC-64 with -Xmx384m and the image's GC, 10 uploads and 10 downloads of 10MB at once succeed "
            + "right after start, with the same hashes and no OutOfMemoryError")
    void tenUploadsAndTenDownloadsAtOnce() throws Exception {
        try (Stack stack = Stack.release("load")) {
            stack.appEnv("JDK_JAVA_OPTIONS", "-Xmx384m");
            stack.up();
            stack.awaitHealthy("app");
            AppSteps steps = new AppSteps(stack);
            Person admin = steps.signInAdmin();
            String company = steps.createCompany(admin);
            List<Person> customers = new ArrayList<>();
            List<String> issues = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                Person customer = steps.join(admin, "CUSTOMER", List.of(company), null);
                customers.add(customer);
                issues.add(steps.fileIssue(customer, company));
            }

            // First ten uploads at once: two per customer, each to their own issue.
            List<Callable<Outcome>> first = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                Person who = customers.get(i % 5);
                String issue = issues.get(i % 5);
                long seed = 6400 + i;
                first.add(() -> Transfer.upload(who, issue, seed, Transfer.TEN_MB));
            }
            List<Outcome> uploaded = allAtOnce(first);
            assertAllSucceeded(uploaded, 201);
            for (int i = 0; i < 10; i++) {
                assertThat(uploaded.get(i).attachmentId()).as("attachment ID of %s", uploaded.get(i)).isNotBlank();
            }

            // Ten more uploads and ten downloads of the first files, all at once.
            List<Callable<Outcome>> mixed = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                Person who = customers.get(i % 5);
                String issue = issues.get(i % 5);
                long seed = 6500 + i;
                String id = uploaded.get(i).attachmentId();
                mixed.add(() -> Transfer.upload(who, issue, seed, Transfer.TEN_MB));
                mixed.add(() -> Transfer.download(who, issue, id));
            }
            List<Outcome> results = allAtOnce(mixed);
            for (int i = 0; i < 10; i++) {
                Outcome upload = results.get(2 * i);
                Outcome download = results.get(2 * i + 1);
                assertThat(upload.status()).as("%s", upload).isEqualTo(201);
                assertThat(download.status()).as("%s", download).isEqualTo(200);
                assertThat(download.sha256()).as("hash of %s (uploaded as seed %d)", download, 6400 + i)
                        .isEqualTo(Transfer.sha256(6400 + i, Transfer.TEN_MB));
            }

            String metrics = stack.prometheus();
            List<Sample> heapMax = Metrics.samples(metrics, "jvm_memory_max_bytes").stream()
                    .filter(s -> "heap".equals(s.label("area")) && s.value() > 0)
                    .toList();
            double heap = heapMax.stream().mapToDouble(Sample::value).sum();
            TreeSet<String> collectors = new TreeSet<>();
            for (String metric : List.of("jvm_gc_pause_seconds_count", "jvm_gc_pause_seconds_max")) {
                Metrics.samples(metrics, metric).forEach(s -> {
                    if (s.label("gc") != null) {
                        collectors.add(s.label("gc"));
                    }
                });
            }
            System.out.printf("S0-AC-64 heap max (sum of jvm_memory_max_bytes, area=heap): %.0f bytes (%.1f MB); "
                    + "pools %s; garbage collectors %s%n", heap, heap / 1024 / 1024, heapMax, collectors);
            assertThat(heap).as("heap max from jvm_memory_max_bytes").isPositive().isLessThanOrEqualTo(MAX_HEAP);

            JsonNode state = stack.inspect("app").path("State");
            assertThat(state.path("OOMKilled").asBoolean(true)).as("app OOMKilled, state %s", state).isFalse();
            assertThat(state.path("Running").asBoolean(false)).as("app running, state %s", state).isTrue();
            assertThat(stack.inspect("app").path("RestartCount").asInt(-1)).as("app restart count").isZero();
            assertThat(stack.logs("app")).as("app log").doesNotContain("OutOfMemoryError");
        }
    }

    private static void assertAllSucceeded(List<Outcome> outcomes, int status) {
        for (Outcome outcome : outcomes) {
            assertThat(outcome.status()).as("%s", outcome).isEqualTo(status);
        }
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
