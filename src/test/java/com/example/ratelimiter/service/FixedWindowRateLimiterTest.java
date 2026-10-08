package com.example.ratelimiter.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ratelimiter.model.RateLimitAlgorithm;
import com.example.ratelimiter.model.RateLimitResponse;
import com.example.ratelimiter.support.ConcurrentRunner;
import com.example.ratelimiter.support.MutableClock;
import com.example.ratelimiter.support.TestProperties;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FixedWindowRateLimiterTest {

    private static final int LIMIT = 100;
    private static final Instant WINDOW_START = Instant.parse("2026-01-01T10:00:00Z");

    private MutableClock clock;
    private FixedWindowRateLimiter limiter;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(WINDOW_START);
        limiter = new FixedWindowRateLimiter(TestProperties.defaults(), clock);
    }

    @Test
    void firstRequestIsAllowed() {
        RateLimitResponse response = limiter.tryConsume("alice");

        assertThat(response.allowed()).isTrue();
        assertThat(response.algorithm()).isEqualTo(RateLimitAlgorithm.FIXED_WINDOW);
        assertThat(response.remaining()).isEqualTo(LIMIT - 1);
        assertThat(response.limit()).isEqualTo(LIMIT);
    }

    @Test
    void hundredRequestsAreAllowed() {
        assertThat(sendRequests("alice", LIMIT)).isEqualTo(LIMIT);
    }

    @Test
    void hundredAndFirstRequestIsRejected() {
        sendRequests("alice", LIMIT);

        RateLimitResponse response = limiter.tryConsume("alice");

        assertThat(response.allowed()).isFalse();
        assertThat(response.remaining()).isZero();
        assertThat(response.retryAfterSeconds()).isEqualTo(60);
    }

    @Test
    void windowExpirationResetsCounter() {
        sendRequests("alice", LIMIT);
        assertThat(limiter.tryConsume("alice").allowed()).isFalse();

        clock.advance(Duration.ofSeconds(60));

        RateLimitResponse response = limiter.tryConsume("alice");
        assertThat(response.allowed()).isTrue();
        assertThat(response.remaining()).isEqualTo(LIMIT - 1);
    }

    @Test
    void differentUsersHaveIndependentCounters() {
        sendRequests("alice", LIMIT);

        assertThat(limiter.tryConsume("alice").allowed()).isFalse();
        assertThat(limiter.tryConsume("bob").allowed()).isTrue();
    }

    @Test
    @DisplayName("Boundary problem: 200 requests pass within one second despite a 100/minute limit")
    void fixedWindowBoundaryProblem() {
        clock.setInstant(Instant.parse("2026-01-01T10:00:59Z"));
        int allowedBeforeBoundary = sendRequests("alice", LIMIT);

        clock.setInstant(Instant.parse("2026-01-01T10:01:00Z"));
        int allowedAfterBoundary = sendRequests("alice", LIMIT);

        // Both windows are respected individually...
        assertThat(allowedBeforeBoundary).isEqualTo(LIMIT);
        assertThat(allowedAfterBoundary).isEqualTo(LIMIT);
        // ...yet 2x the limit got through in ~1 second. The Token Bucket fixes this.
        assertThat(allowedBeforeBoundary + allowedAfterBoundary).isEqualTo(2 * LIMIT);
    }

    @Test
    void concurrentRequestsNeverExceedLimit() throws Exception {
        int allowed = ConcurrentRunner.countAllowed(32, 1000, () -> limiter.tryConsume("alice").allowed());

        assertThat(allowed).isEqualTo(LIMIT);
    }

    private int sendRequests(String userId, int count) {
        int allowed = 0;
        for (int i = 0; i < count; i++) {
            if (limiter.tryConsume(userId).allowed()) {
                allowed++;
            }
        }
        return allowed;
    }
}
