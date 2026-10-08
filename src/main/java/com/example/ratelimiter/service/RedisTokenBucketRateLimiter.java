package com.example.ratelimiter.service;

import com.example.ratelimiter.config.RateLimitProperties;
import com.example.ratelimiter.model.RateLimitAlgorithm;
import com.example.ratelimiter.model.RateLimitPlan;
import com.example.ratelimiter.model.RateLimitResponse;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

/**
 * Implementation 3: distributed Token Bucket whose state lives in Redis.
 * <p>
 * Every application instance runs the same Lua script against the same Redis key, so a user has
 * exactly one bucket no matter which server handles the request. The script reads, refills, checks,
 * consumes and writes in one atomic step (Redis runs scripts one at a time), so concurrent requests
 * cannot both spend the same token.
 */
@Service
public class RedisTokenBucketRateLimiter implements RateLimiterService {

    private static final Logger log = LoggerFactory.getLogger(RedisTokenBucketRateLimiter.class);

    private static final int ALLOWED_INDEX = 0;
    private static final int REMAINING_INDEX = 1;
    private static final int RETRY_AFTER_MILLIS_INDEX = 2;
    private static final long ALLOWED = 1L;
    private static final long MILLIS_PER_SECOND = 1000L;
    private static final long DEFAULT_RETRY_AFTER_SECONDS = 1L;

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<List<Long>> tokenBucketScript;
    private final RateLimitPlanResolver planResolver;
    private final RateLimitProperties.Redis redisProperties;

    public RedisTokenBucketRateLimiter(StringRedisTemplate redisTemplate,
                                       RedisScript<List<Long>> tokenBucketScript,
                                       RateLimitPlanResolver planResolver,
                                       RateLimitProperties properties) {
        this.redisTemplate = redisTemplate;
        this.tokenBucketScript = tokenBucketScript;
        this.planResolver = planResolver;
        this.redisProperties = properties.redis();
    }

    @Override
    public RateLimitAlgorithm algorithm() {
        return RateLimitAlgorithm.REDIS_TOKEN_BUCKET;
    }

    @Override
    public RateLimitResponse tryConsume(String userId) {
        RateLimitPlan plan = planResolver.resolve(userId);
        String key = keyFor(userId);
        try {
            RateLimitResponse response = executeScript(key, plan);
            log.info("user={} key={} allowed={} remaining={}", userId, key, response.allowed(), response.remaining());
            return response;
        } catch (DataAccessException ex) {
            return handleRedisFailure(userId, plan, ex);
        }
    }

    public String keyFor(String userId) {
        return redisProperties.keyPrefix() + userId;
    }

    private RateLimitResponse executeScript(String key, RateLimitPlan plan) {
        List<Long> result = redisTemplate.execute(
                tokenBucketScript,
                List.of(key),
                String.valueOf(plan.capacity()),
                String.valueOf(plan.refillRatePerSecond()),
                String.valueOf(redisProperties.ttlSeconds()));

        if (result == null || result.size() <= RETRY_AFTER_MILLIS_INDEX) {
            throw new IllegalStateException("Unexpected token bucket script result: " + result);
        }
        if (result.get(ALLOWED_INDEX) == ALLOWED) {
            return RateLimitResponse.allowed(algorithm(), plan.capacity(), result.get(REMAINING_INDEX));
        }
        long retryAfterSeconds = Math.max(1, (result.get(RETRY_AFTER_MILLIS_INDEX) + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND);
        return RateLimitResponse.rejected(algorithm(), plan.capacity(), retryAfterSeconds);
    }

    /**
     * Redis is unreachable: apply the configured policy, and always log it - never swallow silently.
     * fail-open favours availability, fail-closed favours strict enforcement.
     */
    private RateLimitResponse handleRedisFailure(String userId, RateLimitPlan plan, DataAccessException ex) {
        boolean failOpen = redisProperties.failOpen();
        log.error("Redis unavailable for user={} - fail-open={} so request is {}: {}",
                userId, failOpen, failOpen ? "ALLOWED" : "REJECTED", ex.getMessage());
        return failOpen
                ? RateLimitResponse.allowed(algorithm(), plan.capacity(), 0)
                : RateLimitResponse.rejected(algorithm(), plan.capacity(), DEFAULT_RETRY_AFTER_SECONDS);
    }
}
