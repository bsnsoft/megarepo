package de.bsnsoft.megarepo.repository.firewall;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class EvaluationThrottleTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-08-05T10:00:00Z"));

    @Test
    @DisplayName("the first claim wins and repeats inside the interval do not")
    void repeatsInsideTheIntervalAreRefused() {
        EvaluationThrottle throttle = new EvaluationThrottle(Duration.ofMinutes(10), 100, clock);

        assertThat(throttle.claim("repo", "a.jar")).isTrue();
        assertThat(throttle.claim("repo", "a.jar")).isFalse();
        assertThat(throttle.claim("repo", "a.jar")).isFalse();
    }

    @Test
    @DisplayName("a different path in the same repository is its own slot")
    void keyCoversRepositoryAndPath() {
        EvaluationThrottle throttle = new EvaluationThrottle(Duration.ofMinutes(10), 100, clock);

        assertThat(throttle.claim("repo", "a.jar")).isTrue();
        assertThat(throttle.claim("repo", "b.jar")).isTrue();
        assertThat(throttle.claim("other", "a.jar")).isTrue();
    }

    @Test
    @DisplayName("the slot reopens once the interval has passed")
    void slotReopensAfterTheInterval() {
        EvaluationThrottle throttle = new EvaluationThrottle(Duration.ofMinutes(10), 100, clock);
        assertThat(throttle.claim("repo", "a.jar")).isTrue();

        clock.advance(Duration.ofMinutes(9));
        assertThat(throttle.claim("repo", "a.jar")).isFalse();

        clock.advance(Duration.ofMinutes(2));
        assertThat(throttle.claim("repo", "a.jar")).isTrue();
    }

    @Test
    @DisplayName("a zero interval switches throttling off entirely")
    void zeroIntervalDisablesThrottling() {
        EvaluationThrottle throttle = new EvaluationThrottle(Duration.ZERO, 100, clock);

        assertThat(throttle.claim("repo", "a.jar")).isTrue();
        assertThat(throttle.claim("repo", "a.jar")).isTrue();
        assertThat(throttle.size()).isZero();
    }

    @Test
    @DisplayName("memory stays bounded no matter how many distinct paths arrive")
    void memoryStaysBounded() {
        EvaluationThrottle throttle = new EvaluationThrottle(Duration.ofMinutes(10), 50, clock);

        for (int i = 0; i < 5_000; i++) {
            throttle.claim("repo", "artifact-" + i + ".jar");
        }

        assertThat(throttle.size()).isLessThanOrEqualTo(51);
    }

    @Test
    @DisplayName("expired entries are swept before the map is dropped wholesale")
    void expiredEntriesAreSweptFirst() {
        EvaluationThrottle throttle = new EvaluationThrottle(Duration.ofMinutes(10), 10, clock);
        for (int i = 0; i < 10; i++) {
            throttle.claim("repo", "old-" + i + ".jar");
        }

        clock.advance(Duration.ofMinutes(11));
        throttle.claim("repo", "fresh.jar");

        assertThat(throttle.size())
                .as("the ten stale entries are gone, the fresh claim survives")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("two threads racing on the same artifact: exactly one is allowed through")
    void concurrentClaimsGrantExactlyOne() throws Exception {
        EvaluationThrottle throttle = new EvaluationThrottle(Duration.ofMinutes(10), 1000, clock);
        int threads = 16;
        AtomicInteger granted = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.execute(() -> {
                    try {
                        start.await();
                        if (throttle.claim("repo", "hot.jar")) {
                            granted.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(granted.get()).isEqualTo(1);
    }

    /** A clock the test moves by hand, so nothing has to sleep. */
    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration amount) {
            now = now.plus(amount);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
