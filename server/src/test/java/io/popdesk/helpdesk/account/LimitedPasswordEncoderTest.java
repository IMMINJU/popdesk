package io.popdesk.helpdesk.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The limit on password hashes computed at a time: no more than {@link LimitedPasswordEncoder#AT_ONCE}
 * run together, the rest wait and still finish, and what comes out is what the encoder inside gives.
 */
class LimitedPasswordEncoderTest {

    private static final String PASSWORD = "correct horse battery staple";
    private static final int THREADS = 12;

    /** Counts the computations that are inside it at the same moment. */
    private static final class Counting implements PasswordEncoder {

        private final PasswordEncoder encoder;
        private final AtomicInteger running = new AtomicInteger();
        private final AtomicInteger most = new AtomicInteger();
        private final CountDownLatch full = new CountDownLatch(LimitedPasswordEncoder.AT_ONCE);
        private final CountDownLatch overFull = new CountDownLatch(LimitedPasswordEncoder.AT_ONCE + 1);
        private final CountDownLatch go;

        Counting(PasswordEncoder encoder, CountDownLatch go) {
            this.encoder = encoder;
            this.go = go;
        }

        @Override
        public String encode(CharSequence rawPassword) {
            enter();
            try {
                return encoder.encode(rawPassword);
            } finally {
                running.decrementAndGet();
            }
        }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            enter();
            try {
                return encoder.matches(rawPassword, encodedPassword);
            } finally {
                running.decrementAndGet();
            }
        }

        private void enter() {
            most.accumulateAndGet(running.incrementAndGet(), Math::max);
            full.countDown();
            overFull.countDown();
            try {
                go.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    /** Gives back the password itself, so a test of the waiting computes no hash. */
    private static final class Plain implements PasswordEncoder {

        @Override
        public String encode(CharSequence rawPassword) {
            return rawPassword.toString();
        }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            return rawPassword.toString().equals(encodedPassword);
        }
    }

    @Test
    @DisplayName("of 12 hashes and matches asked at once, 4 run and the other 8 wait until a place is free")
    void noMoreThanFourRunAtOnce() throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        Counting counting = new Counting(new Plain(), go);
        PasswordEncoder limited = new LimitedPasswordEncoder(counting);
        ExecutorService threads = Executors.newFixedThreadPool(THREADS);
        try {
            List<Future<Boolean>> asked = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                boolean hashing = i % 2 == 0;
                asked.add(threads.submit(() -> hashing ? PASSWORD.equals(limited.encode(PASSWORD))
                        : limited.matches(PASSWORD, PASSWORD)));
            }

            assertThat(counting.full.await(10, TimeUnit.SECONDS)).as("4 computations started").isTrue();
            assertThat(counting.overFull.await(300, TimeUnit.MILLISECONDS))
                    .as("a 5th computation started while 4 were running").isFalse();
            go.countDown();

            for (Future<Boolean> one : asked) {
                assertThat(one.get(10, TimeUnit.SECONDS)).as("the result of a computation that waited").isTrue();
            }
        } finally {
            go.countDown();
            threads.shutdownNow();
        }
        assertThat(counting.most.get()).as("most computations running at the same moment")
                .isEqualTo(LimitedPasswordEncoder.AT_ONCE);
    }

    @Test
    @DisplayName("hashes made through the limit are Argon2id with the app's parameters and match their password, "
            + "also when 12 are asked at once")
    void hashesAreTheOnesOfTheEncoderInside() throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        go.countDown();
        Counting counting = new Counting(PasswordHashes.encoder(), go);
        PasswordEncoder limited = new LimitedPasswordEncoder(counting);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(THREADS);
        List<String> hashes = new ArrayList<>();
        try {
            List<Future<String>> asked = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                asked.add(threads.submit(() -> {
                    start.await();
                    String hash = limited.encode(PASSWORD);
                    assertThat(limited.matches(PASSWORD, hash)).as("the password matches its hash").isTrue();
                    assertThat(limited.matches(PASSWORD + "x", hash)).as("another password matches").isFalse();
                    return hash;
                }));
            }
            start.countDown();
            for (Future<String> one : asked) {
                hashes.add(one.get(120, TimeUnit.SECONDS));
            }
        } finally {
            threads.shutdownNow();
        }

        assertThat(hashes).hasSize(THREADS).doesNotHaveDuplicates()
                .allSatisfy(hash -> assertThat(hash).startsWith("$argon2id$v=19$m=19456,t=2,p=1$"));
        assertThat(hashes).allSatisfy(hash -> {
            assertThat(PasswordHashes.encoder().matches(PASSWORD, hash)).as("matches without the limit").isTrue();
            assertThat(limited.upgradeEncoding(hash)).isEqualTo(PasswordHashes.encoder().upgradeEncoding(hash));
        });
        assertThat(limited.matches(PASSWORD, PasswordHashes.encoder().encode(PASSWORD)))
                .as("a hash made without the limit matches through it").isTrue();
        assertThat(counting.most.get()).as("most computations running at the same moment")
                .isLessThanOrEqualTo(LimitedPasswordEncoder.AT_ONCE);
    }

    @Test
    @DisplayName("a thread interrupted while it waits its turn ends with an exception and stays interrupted, and "
            + "computes nothing")
    void interruptedWhileWaiting() throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        Counting counting = new Counting(new Plain(), go);
        PasswordEncoder limited = new LimitedPasswordEncoder(counting);
        ExecutorService threads = Executors.newFixedThreadPool(LimitedPasswordEncoder.AT_ONCE);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicBoolean stillInterrupted = new AtomicBoolean();
        try {
            List<Future<String>> running = new ArrayList<>();
            for (int i = 0; i < LimitedPasswordEncoder.AT_ONCE; i++) {
                running.add(threads.submit(() -> limited.encode(PASSWORD)));
            }
            assertThat(counting.full.await(10, TimeUnit.SECONDS)).as("4 computations started").isTrue();
            Thread waiting = new Thread(() -> {
                try {
                    limited.encode(PASSWORD);
                } catch (RuntimeException e) {
                    thrown.set(e);
                }
                stillInterrupted.set(Thread.currentThread().isInterrupted());
            });
            waiting.start();

            waiting.interrupt();
            waiting.join(10_000);

            assertThat(waiting.isAlive()).as("the interrupted thread is still waiting").isFalse();
            go.countDown();
            for (Future<String> one : running) {
                assertThat(one.get(10, TimeUnit.SECONDS)).isEqualTo(PASSWORD);
            }
        } finally {
            go.countDown();
            threads.shutdownNow();
        }
        assertThat(thrown.get()).isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(InterruptedException.class);
        assertThat(stillInterrupted).as("the thread's interrupted status after the exception").isTrue();
        assertThat(counting.most.get()).as("computations that ran").isEqualTo(LimitedPasswordEncoder.AT_ONCE);
    }
}
