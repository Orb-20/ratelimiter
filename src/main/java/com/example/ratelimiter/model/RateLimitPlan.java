package com.example.ratelimiter.model;

/**
 * Token-bucket limits applied to a user. Different users (e.g. FREE vs PREMIUM tiers)
 * can be given different plans through a {@link com.example.ratelimiter.service.RateLimitPlanResolver}.
 *
 * @param capacity            maximum number of tokens the bucket can hold (burst size)
 * @param refillRatePerSecond tokens added per second
 */
public record RateLimitPlan(long capacity, double refillRatePerSecond) {

    public RateLimitPlan {
        if (capacity <= 0 || refillRatePerSecond <= 0) {
            throw new IllegalArgumentException("capacity and refillRatePerSecond must be > 0");
        }
    }
}
