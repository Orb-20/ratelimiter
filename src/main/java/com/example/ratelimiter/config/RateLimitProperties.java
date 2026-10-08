package com.example.ratelimiter.config;

import com.example.ratelimiter.model.RateLimitAlgorithm;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * All rate-limit settings, bound from the {@code rate-limit.*} properties.
 */
@ConfigurationProperties(prefix = "rate-limit")
public record RateLimitProperties(
        @DefaultValue("TOKEN_BUCKET") RateLimitAlgorithm defaultAlgorithm,
        @DefaultValue Counter counter,
        @DefaultValue TokenBucket tokenBucket,
        @DefaultValue Redis redis) {

    public record Counter(
            @DefaultValue("100") long maxRequests,
            @DefaultValue("60") long windowSeconds) {

        public Counter {
            requirePositive(maxRequests, "rate-limit.counter.max-requests");
            requirePositive(windowSeconds, "rate-limit.counter.window-seconds");
        }
    }

    public record TokenBucket(
            @DefaultValue("100") long capacity,
            @DefaultValue("10") double refillRate) {

        public TokenBucket {
            requirePositive(capacity, "rate-limit.token-bucket.capacity");
            if (refillRate <= 0) {
                throw new IllegalArgumentException("rate-limit.token-bucket.refill-rate must be > 0");
            }
        }
    }

    public record Redis(
            @DefaultValue("rate-limit:token-bucket:") String keyPrefix,
            @DefaultValue("3600") long ttlSeconds,
            @DefaultValue("false") boolean failOpen) {

        public Redis {
            requirePositive(ttlSeconds, "rate-limit.redis.ttl-seconds");
        }
    }

    private static void requirePositive(long value, String property) {
        if (value <= 0) {
            throw new IllegalArgumentException(property + " must be > 0");
        }
    }
}
