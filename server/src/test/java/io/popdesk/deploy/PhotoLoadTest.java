package io.popdesk.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.deploy.AppSteps.Person;
import io.popdesk.deploy.Metrics.Sample;
import io.popdesk.photo.PhotoSteps;
import io.popdesk.support.ApiResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Ten profile photos uploaded at once on the real image with a 384MB heap (slice 1a premortem
 * section 4), in the way of {@link AttachmentLoadTest}. Each photo is the worst the limits allow: a
 * file of exactly 2 MB that decodes to 25 million pixels of three bytes. The app decodes two at a
 * time and turns the others away with 503, so both answers are right; what must not happen is an
 * {@code OutOfMemoryError} or a restart.
 */
@Tag("deploy")
class PhotoLoadTest {

    private static final long MAX_HEAP = 384L * 1024 * 1024;
    private static final int UPLOADS = 10;

    private static final Once<Stack> RUNNING = new Once<>("starting the image with -Xmx384m", PhotoLoadTest::start);

    @AfterAll
    static void stop() {
        Stack stack = RUNNING.peek();
        if (stack != null) {
            stack.close();
        }
    }

    private static Stack start() {
        Stack stack = Stack.release("photo-load");
        try {
            stack.appEnv("JDK_JAVA_OPTIONS", "-Xmx384m");
            stack.up();
            stack.awaitHealthy("app");
            return stack;
        } catch (AssertionError | RuntimeException e) {
            stack.close();
            throw e;
        }
    }

    @Test
    @DisplayName("S1A-AC-88 with -Xmx384m, 10 uploads at once of 2 MB photos of 25 million pixels each end in 201 "
            + "or in 503 with Retry-After, the stored ones are served, and the app neither runs out of memory "
            + "nor restarts")
    void tenPhotosAtOnce() throws InterruptedException {
        Stack stack = RUNNING.get();
        AppSteps steps = new AppSteps(stack);
        Person admin = steps.signInAdmin();
        String company = steps.createCompany(admin);
        List<Person> customers = new ArrayList<>();
        for (int i = 0; i < UPLOADS; i++) {
            customers.add(steps.join(admin, "CUSTOMER", List.of(company), null));
        }
        byte[] photo = PhotoSteps.padPng(PhotoSteps.solidRgbPng(5000, 5000), PhotoSteps.MAX_BYTES, 88);

        List<ApiResponse> answers = allAtOnce(customers, photo);

        int stored = 0;
        for (int i = 0; i < UPLOADS; i++) {
            ApiResponse answer = answers.get(i);
            Person who = customers.get(i);
            ApiResponse served = who.client().get("/api/portal/photos/" + who.accountId()).send();
            assertThat(answer.status()).as("%s: %s", answer, answer.snippet()).isIn(201, 503);
            if (answer.status() == 201) {
                stored++;
                PhotoSteps.assertScaled(PhotoSteps.decodePng(served), 5000, 5000);
            } else {
                assertThat(answer.errorCode()).isEqualTo("SERVER_BUSY");
                assertThat(answer.header("Retry-After")).as("Retry-After of %s", answer).isNotBlank();
                assertThat(served.status()).as("photo of an account whose upload got 503: %s", served).isEqualTo(404);
            }
        }
        assertThat(stored).as("uploads answered with 201").isPositive();

        Person later = steps.join(admin, "CUSTOMER", List.of(company), null);
        ApiResponse afterwards = later.client().post("/api/portal/me/photo")
                .file("file", "photo.png", "image/png", photo).send();
        assertThat(afterwards.status()).as("an upload after the ten: %s", afterwards.snippet()).isEqualTo(201);

        List<Sample> heapMax = Metrics.samples(stack.prometheus(), "jvm_memory_max_bytes").stream()
                .filter(s -> "heap".equals(s.label("area")) && s.value() > 0)
                .toList();
        assertThat(heapMax.stream().mapToDouble(Sample::value).sum()).as("heap max from jvm_memory_max_bytes")
                .isPositive().isLessThanOrEqualTo(MAX_HEAP);
        JsonNode state = stack.inspect("app").path("State");
        assertThat(state.path("OOMKilled").asBoolean(true)).as("app OOMKilled, state %s", state).isFalse();
        assertThat(state.path("Running").asBoolean(false)).as("app running, state %s", state).isTrue();
        assertThat(stack.inspect("app").path("RestartCount").asInt(-1)).as("app restart count").isZero();
        assertThat(stack.logs("app")).as("app log").doesNotContain("OutOfMemoryError");
    }

    /** Each customer uploads {@code photo} in a thread of their own, all released together. */
    private static List<ApiResponse> allAtOnce(List<Person> customers, byte[] photo) throws InterruptedException {
        CountDownLatch go = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(customers.size())) {
            List<Future<ApiResponse>> futures = new ArrayList<>();
            for (Person who : customers) {
                var request = who.client().post("/api/portal/me/photo").file("file", "photo.png", "image/png", photo);
                futures.add(pool.submit(() -> {
                    go.await();
                    return request.send();
                }));
            }
            go.countDown();
            List<ApiResponse> out = new ArrayList<>();
            for (Future<ApiResponse> f : futures) {
                try {
                    out.add(f.get());
                } catch (ExecutionException e) {
                    throw new AssertionError("An upload broke: " + e.getCause(), e.getCause());
                }
            }
            return out;
        }
    }
}
