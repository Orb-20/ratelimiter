package com.example.ratelimiter.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ratelimiter.model.RateLimitAlgorithm;
import com.example.ratelimiter.model.RateLimitPlan;
import com.example.ratelimiter.model.RateLimitResponse;
import com.example.ratelimiter.support.TestProperties;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/** Unit tests with a mocked Redis: result mapping, key design and the failure policy. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RedisTokenBucketRateLimiterTest {

    private static final RateLimitPlan PLAN = new RateLimitPlan(100, 10);
    private static final String KEY = "rate-limit:token-bucket:alice";

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private RedisScript<List<Long>> script;
    @Mock
    private RateLimitPlanResolver planResolver;

    @BeforeEach
    void setUp() {
        when(planResolver.resolve(anyString())).thenReturn(PLAN);
    }

    @Test
    void buildsKeyFromPrefixAndUserId() {
        assertThat(limiter(false).keyFor("alice")).isEqualTo(KEY);
    }

    @Test
    void passesKeyAndPlanToScript() {
        stubScriptResult(List.of(1L, 99L, 0L));

        limiter(false).tryConsume("alice");

        verify(redisTemplate).execute(script, List.of(KEY), "100", "10.0", "3600");
    }

    @Test
    void mapsAllowedResult() {
        stubScriptResult(List.of(1L, 73L, 0L));

        RateLimitResponse response = limiter(false).tryConsume("alice");

        assertThat(response.allowed()).isTrue();
        assertThat(response.algorithm()).isEqualTo(RateLimitAlgorithm.REDIS_TOKEN_BUCKET);
        assertThat(response.remaining()).isEqualTo(73);
        assertThat(response.limit()).isEqualTo(100);
    }

    @Test
    void mapsRejectedResultAndRoundsRetryAfterUp() {
        stubScriptResult(List.of(0L, 0L, 1200L));

        RateLimitResponse response = limiter(false).tryConsume("alice");

        assertThat(response.allowed()).isFalse();
        assertThat(response.remaining()).isZero();
        assertThat(response.retryAfterSeconds()).isEqualTo(2);
    }

    @Test
    void failClosedRejectsWhenRedisIsDown() {
        stubRedisDown();

        RateLimitResponse response = limiter(false).tryConsume("alice");

        assertThat(response.allowed()).isFalse();
        assertThat(response.retryAfterSeconds()).isEqualTo(1);
    }

    @Test
    void failOpenAllowsWhenRedisIsDown() {
        stubRedisDown();

        RateLimitResponse response = limiter(true).tryConsume("alice");

        assertThat(response.allowed()).isTrue();
    }

    private RedisTokenBucketRateLimiter limiter(boolean failOpen) {
        return new RedisTokenBucketRateLimiter(redisTemplate, script, planResolver,
                TestProperties.of(100, 60, 100, 10, 3600, failOpen));
    }

    private void stubScriptResult(List<Long> result) {
        when(redisTemplate.execute(eq(script), eq(List.of(KEY)), any(), any(), any())).thenReturn(result);
    }

    private void stubRedisDown() {
        when(redisTemplate.execute(eq(script), eq(List.of(KEY)), any(), any(), any()))
                .thenThrow(new RedisConnectionFailureException("Connection refused"));
    }
}
