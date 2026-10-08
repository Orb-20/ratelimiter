package com.example.ratelimiter.model;

/**
 * Per-user state of the in-memory token bucket.
 * <p>
 * Methods are {@code synchronized} on the bucket itself, so the lock is per user:
 * requests from different users never block each other.
 */
public class BucketState {

    private static final double MILLIS_PER_SECOND = 1000.0;
    private static final double ONE_TOKEN = 1.0;

    private double tokens;
    private long lastRefillTime;

    public BucketState(double tokens, long lastRefillTime) {
        this.tokens = tokens;
        this.lastRefillTime = lastRefillTime;
    }

    public static BucketState full(RateLimitPlan plan, long nowMillis) {
        return new BucketState(plan.capacity(), nowMillis);
    }

    /**
     * Lazily refills the bucket based on elapsed time, then consumes one token if available.
     *
     * @return true if a token was consumed
     */
    public synchronized boolean tryConsume(RateLimitPlan plan, long nowMillis) {
        refill(plan, nowMillis);
        if (tokens < ONE_TOKEN) {
            return false;
        }
        tokens -= ONE_TOKEN;
        return true;
    }

    private void refill(RateLimitPlan plan, long nowMillis) {
        long elapsedMillis = Math.max(0, nowMillis - lastRefillTime);
        double newTokens = elapsedMillis / MILLIS_PER_SECOND * plan.refillRatePerSecond();
        tokens = Math.min(plan.capacity(), tokens + newTokens);
        lastRefillTime = nowMillis;
    }

    /** Whole tokens currently available. */
    public synchronized long availableTokens() {
        return (long) Math.floor(tokens);
    }

    /** Seconds (rounded up, at least 1) until one full token is available. */
    public synchronized long secondsUntilNextToken(RateLimitPlan plan) {
        double missing = Math.max(0, ONE_TOKEN - tokens);
        return Math.max(1, (long) Math.ceil(missing / plan.refillRatePerSecond()));
    }
}
