package com.example.ratelimiter.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ratelimiter.config.RateLimitProperties;
import com.example.ratelimiter.model.RateLimitPlan;
import com.example.ratelimiter.model.RateLimitResponse;
import com.example.ratelimiter.support.ConcurrentRunner;
import com.example.ratelimiter.support.TestProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Runs against the local Redis (localhost:6379). Skipped automatically when Redis is not running.
 */
@SpringBootTest
@EnabledIf("com.example.ratelimiter.support.RedisAvailability#isRunning")
class RedisTokenBucketRateLimiterIntegrationTest {

    private static final long TTL_SECONDS = 3600;
    /** Effectively no refill during a test, so token counts are deterministic. */
    private static final double NO_REFILL = 0.001;

    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private RedisScript<List<Long>> tokenBucketScript;

    private final List<String> usedKeys = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        redisTemplate.delete(usedKeys);
    }

    @Test
    void redisConnectionWorks() {
        String pong = redisTemplate.execute(connection -> connection.ping(), true);

        assertThat(pong).isEqualToIgnoringCase("PONG");
    }

    @Test
    void newBucketIsInitialisedFullAndStoredAsHash() {
        RedisTokenBucketRateLimiter limiter = limiter(new RateLimitPlan(10, NO_REFILL));
        String user = newUser(limiter);

        RateLimitResponse response = limiter.tryConsume(user);

        assertThat(response.allowed()).isTrue();
        assertThat(response.remaining()).isEqualTo(9);
        assertThat(redisTemplate.opsForHash().keys(limiter.keyFor(user)))
                .containsExactlyInAnyOrder("tokens", "lastRefill");
    }

    @Test
    void requestsConsumeTokens() {
        RedisTokenBucketRateLimiter limiter = limiter(new RateLimitPlan(10, NO_REFILL));
        String user = newUser(limiter);

        limiter.tryConsume(user);
        limiter.tryConsume(user);

        assertThat(limiter.tryConsume(user).remaining()).isEqualTo(7);
    }

    @Test
    void emptyBucketIsRejectedWithRetryAfter() {
        RedisTokenBucketRateLimiter limiter = limiter(new RateLimitPlan(5, 10));
        String user = newUser(limiter);
        drain(limiter, user, 5);

        RateLimitResponse response = limiter.tryConsume(user);

        assertThat(response.allowed()).isFalse();
        assertThat(response.remaining()).isZero();
        assertThat(response.retryAfterSeconds()).isEqualTo(1);
    }

    @Test
    void tokensRefillOverTime() throws InterruptedException {
        RateLimitPlan plan = new RateLimitPlan(5, 20);
        RedisTokenBucketRateLimiter limiter = limiter(plan);
        String user = newUser(limiter);
        drain(limiter, user, 5);
        assertThat(limiter.tryConsume(user).allowed()).isFalse();

        Thread.sleep(250); // 0.25s * 20/s = ~5 tokens

        assertThat(limiter.tryConsume(user).allowed()).isTrue();
    }

    @Test
    void bucketNeverExceedsCapacity() throws InterruptedException {
        RedisTokenBucketRateLimiter limiter = limiter(new RateLimitPlan(5, 1000));
        String user = newUser(limiter);
        limiter.tryConsume(user);

        Thread.sleep(200); // would refill 200 tokens without the cap

        assertThat(limiter.tryConsume(user).remaining()).isEqualTo(4);
        double storedTokens = Double.parseDouble((String) redisTemplate.opsForHash().get(limiter.keyFor(user), "tokens"));
        assertThat(storedTokens).isLessThanOrEqualTo(5);
    }

    @Test
    void usersHaveIndependentBuckets() {
        RedisTokenBucketRateLimiter limiter = limiter(new RateLimitPlan(3, NO_REFILL));
        String alice = newUser(limiter);
        String bob = newUser(limiter);
        drain(limiter, alice, 3);

        assertThat(limiter.tryConsume(alice).allowed()).isFalse();
        assertThat(limiter.tryConsume(bob).remaining()).isEqualTo(2);
    }

    @Test
    void keyHasTtlThatIsRefreshedOnEachRequest() {
        RedisTokenBucketRateLimiter limiter = limiter(new RateLimitPlan(10, NO_REFILL));
        String user = newUser(limiter);
        String key = limiter.keyFor(user);

        limiter.tryConsume(user);
        assertThat(redisTemplate.getExpire(key)).isBetween(1L, TTL_SECONDS);

        redisTemplate.expire(key, Duration.ofSeconds(5));
        limiter.tryConsume(user);
        assertThat(redisTemplate.getExpire(key)).isGreaterThan(5L).isLessThanOrEqualTo(TTL_SECONDS);
    }

    @Test
    void concurrentRequestsConsumeExactlyCapacityTokens() throws Exception {
        int capacity = 50;
        RedisTokenBucketRateLimiter limiter = limiter(new RateLimitPlan(capacity, NO_REFILL));
        String user = newUser(limiter);

        int allowed = ConcurrentRunner.countAllowed(32, 500, () -> limiter.tryConsume(user).allowed());

        // With GET -> calculate -> SET this would over-admit; the Lua script makes it exact.
        assertThat(allowed).isEqualTo(capacity);
    }

    @Test
    void separateServerInstancesShareOneBucket() throws Exception {
        int capacity = 30;
        RateLimitPlan plan = new RateLimitPlan(capacity, NO_REFILL);
        RedisTokenBucketRateLimiter server1 = limiter(plan);
        LettuceConnectionFactory secondConnection = new LettuceConnectionFactory(new RedisStandaloneConfiguration());
        secondConnection.afterPropertiesSet();
        secondConnection.start();
        try {
            RedisTokenBucketRateLimiter server2 = limiter(new StringRedisTemplate(secondConnection), plan);
            String user = newUser(server1);

            AtomicInteger requestNumber = new AtomicInteger();

            // Requests alternate between the two "servers", like a round-robin load balancer.
            int allowed = ConcurrentRunner.countAllowed(16, 200, () ->
                    (requestNumber.incrementAndGet() % 2 == 0 ? server1 : server2).tryConsume(user).allowed());

            assertThat(allowed).isEqualTo(capacity);
            assertThat(server2.tryConsume(user).allowed()).isFalse();
        } finally {
            secondConnection.destroy();
        }
    }

    private RedisTokenBucketRateLimiter limiter(RateLimitPlan plan) {
        return limiter(redisTemplate, plan);
    }

    private RedisTokenBucketRateLimiter limiter(StringRedisTemplate template, RateLimitPlan plan) {
        RateLimitProperties properties = TestProperties.of(100, 60, plan.capacity(), plan.refillRatePerSecond(),
                TTL_SECONDS, false);
        return new RedisTokenBucketRateLimiter(template, tokenBucketScript, userId -> plan, properties);
    }

    private String newUser(RedisTokenBucketRateLimiter limiter) {
        String user = "it-" + UUID.randomUUID();
        usedKeys.add(limiter.keyFor(user));
        return user;
    }

    private static void drain(RedisTokenBucketRateLimiter limiter, String user, int tokens) {
        for (int i = 0; i < tokens; i++) {
            assertThat(limiter.tryConsume(user).allowed()).isTrue();
        }
    }
}
