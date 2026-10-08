package com.example.ratelimiter.support;

import com.example.ratelimiter.config.RateLimitProperties;
import com.example.ratelimiter.model.RateLimitAlgorithm;

public final class TestProperties {

    public static final String KEY_PREFIX = "rate-limit:token-bucket:";

    private TestProperties() {
    }

    public static RateLimitProperties of(long maxRequests, long windowSeconds,
                                         long capacity, double refillRate,
                                         long ttlSeconds, boolean failOpen) {
        return new RateLimitProperties(
                RateLimitAlgorithm.TOKEN_BUCKET,
                new RateLimitProperties.Counter(maxRequests, windowSeconds),
                new RateLimitProperties.TokenBucket(capacity, refillRate),
                new RateLimitProperties.Redis(KEY_PREFIX, ttlSeconds, failOpen));
    }

    public static RateLimitProperties defaults() {
        return of(100, 60, 100, 10, 3600, false);
    }
}
