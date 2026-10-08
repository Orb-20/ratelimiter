package com.example.ratelimiter.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.example.ratelimiter.model.RateLimitAlgorithm;
import com.example.ratelimiter.model.RateLimitPlan;
import com.example.ratelimiter.model.RateLimitResponse;
import com.example.ratelimiter.support.ConcurrentRunner;
import com.example.ratelimiter.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TokenBucketRateLimiterTest {

    private static final int CAPACITY = 100;
    private static final double REFILL_RATE = 10;
    private static final RateLimitPlan PLAN = new RateLimitPlan(CAPACITY, REFILL_RATE);

    @Mock
    private RateLimitPlanResolver planResolver;

    private MutableClock clock;
    private TokenBucketRateLimiter limiter;

    @BeforeEach
    void setUp() {
        when(planResolver.resolve(anyString())).thenReturn(PLAN);
        clock = new MutableClock(Instant.parse("2026-01-01T10:00:00Z"));
        limiter = new TokenBucketRateLimiter(planResolver, clock);
    }

    @Test
    void initialBucketContainsCapacityTokens() {
        RateLimitResponse response = limiter.tryConsume("alice");

        assertThat(response.allowed()).isTrue();
        assertThat(response.algorithm()).isEqualTo(RateLimitAlgorithm.TOKEN_BUCKET);
        assertThat(response.remaining()).isEqualTo(CAPACITY - 1);
        assertThat(response.limit()).isEqualTo(CAPACITY);
    }

    @Test
    void eachRequestConsumesOneToken() {
        limiter.tryConsume("alice");
        limiter.tryConsume("alice");

        assertThat(limiter.tryConsume("alice").remaining()).isEqualTo(CAPACITY - 3);
    }

    @Test
    void emptyBucketRejectsRequest() {
        drain("alice");

        RateLimitResponse response = limiter.tryConsume("alice");

        assertThat(response.allowed()).isFalse();
        assertThat(response.remaining()).isZero();
        assertThat(response.retryAfterSeconds()).isEqualTo(1);
    }

    @Test
    void tokensRefillAfterElapsedTime() {
        drain("alice");

        clock.advance(Duration.ofMillis(500)); // 0.5s * 10/s = 5 tokens

        assertThat(limiter.tryConsume("alice").remaining()).isEqualTo(4);
        assertThat(countAllowed("alice", 10)).isEqualTo(4);
    }

    @Test
    void partialTokensAccumulateAcrossRequests() {
        drain("alice");

        clock.advance(Duration.ofMillis(50)); // 0.5 token
        assertThat(limiter.tryConsume("alice").allowed()).isFalse();
        clock.advance(Duration.ofMillis(50)); // 1.0 token
        assertThat(limiter.tryConsume("alice").allowed()).isTrue();
    }

    @Test
    void bucketNeverExceedsCapacity() {
        limiter.tryConsume("alice");

        clock.advance(Duration.ofHours(1)); // would be 36,000 tokens without the cap

        assertThat(limiter.tryConsume("alice").remaining()).isEqualTo(CAPACITY - 1);
        assertThat(countAllowed("alice", 1000)).isEqualTo(CAPACITY - 1);
    }

    @Test
    @DisplayName("Unlike the fixed window, a full burst cannot be repeated right after a boundary")
    void noBoundaryBurst() {
        int firstBurst = countAllowed("alice", CAPACITY);
        clock.advance(Duration.ofSeconds(1)); // only 10 tokens refill
        int secondBurst = countAllowed("alice", CAPACITY);

        assertThat(firstBurst).isEqualTo(CAPACITY);
        assertThat(secondBurst).isEqualTo(10);
    }

    @Test
    void differentUsersHaveIndependentBuckets() {
        drain("alice");

        assertThat(limiter.tryConsume("alice").allowed()).isFalse();
        assertThat(limiter.tryConsume("bob").remaining()).isEqualTo(CAPACITY - 1);
    }

    @Test
    void usesPlanResolvedForEachUser() {
        when(planResolver.resolve("premium-user")).thenReturn(new RateLimitPlan(1000, 100));

        assertThat(limiter.tryConsume("premium-user").remaining()).isEqualTo(999);
        assertThat(limiter.tryConsume("alice").remaining()).isEqualTo(CAPACITY - 1);
    }

    @Test
    void concurrentRequestsConsumeExactlyCapacityTokens() throws Exception {
        int allowed = ConcurrentRunner.countAllowed(32, 1000, () -> limiter.tryConsume("alice").allowed());

        assertThat(allowed).isEqualTo(CAPACITY);
    }

    private void drain(String userId) {
        countAllowed(userId, CAPACITY);
    }

    private int countAllowed(String userId, int requests) {
        int allowed = 0;
        for (int i = 0; i < requests; i++) {
            if (limiter.tryConsume(userId).allowed()) {
                allowed++;
            }
        }
        return allowed;
    }
}
