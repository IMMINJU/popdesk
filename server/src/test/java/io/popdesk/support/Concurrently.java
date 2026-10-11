package io.popdesk.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Sends requests at the same time and returns every response, in the order of the calls.
 *
 * <p>At most {@code threads} run at once. Attachment uploads are limited to 16 at a time
 * (S0-AC-99), so a test that wants every upload to succeed passes 16 or fewer.
 */
public final class Concurrently {

    private Concurrently() {}

    public static List<ApiResponse> send(int threads, List<Supplier<ApiResponse>> calls) {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<ApiResponse>> futures = new ArrayList<>();
            for (Supplier<ApiResponse> call : calls) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return call.get();
                }));
            }
            go.countDown();
            List<ApiResponse> out = new ArrayList<>();
            for (Future<ApiResponse> f : futures) {
                out.add(f.get(120, TimeUnit.SECONDS));
            }
            return out;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof AssertionError a) {
                throw a;
            }
            throw new AssertionError("A concurrent request failed", e.getCause());
        } catch (TimeoutException e) {
            throw new AssertionError("Concurrent requests did not finish in 120 seconds", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for concurrent requests", e);
        } finally {
            pool.shutdownNow();
        }
    }
}
