package com.example.ratelimiter.service;

import com.example.ratelimiter.model.BucketState;
import com.example.ratelimiter.model.RateLimitAlgorithm;
import com.example.ratelimiter.model.RateLimitPlan;
import com.example.ratelimiter.model.RateLimitResponse;
import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Implementation 2: in-memory Token Bucket with lazy refill.
 * <p>
 * No background threads: tokens are topped up from the elapsed time whenever the user makes a request.
 * Thread safety is per user - each {@link BucketState} is its own lock.
 * <p>
 * Limitation: state lives in this JVM only, so N servers give a user N separate buckets.
 */
@Service
public class TokenBucketRateLimiter implements RateLimiterService {

    private static final Logger log = LoggerFactory.getLogger(TokenBucketRateLimiter.class);

    private final ConcurrentMap<String, BucketState> buckets = new ConcurrentHashMap<>();
    private final RateLimitPlanResolver planResolver;
    private final Clock clock;

    public TokenBucketRateLimiter(RateLimitPlanResolver planResolver, Clock clock) {
        this.planResolver = planResolver;
        this.clock = clock;
    }

    @Override
    public RateLimitAlgorithm algorithm() {
        return RateLimitAlgorithm.TOKEN_BUCKET;
    }

    @Override
    public RateLimitResponse tryConsume(String userId) {
        RateLimitPlan plan = planResolver.resolve(userId);
        long now = clock.millis();
        BucketState bucket = buckets.computeIfAbsent(userId, id -> BucketState.full(plan, now));

        RateLimitResponse response;
        synchronized (bucket) {
            response = bucket.tryConsume(plan, now)
                    ? RateLimitResponse.allowed(algorithm(), plan.capacity(), bucket.availableTokens())
                    : RateLimitResponse.rejected(algorithm(), plan.capacity(), bucket.secondsUntilNextToken(plan));
        }

        log.info("user={} algorithm={} allowed={} remaining={}",
                userId, algorithm(), response.allowed(), response.remaining());
        return response;
    }
}
