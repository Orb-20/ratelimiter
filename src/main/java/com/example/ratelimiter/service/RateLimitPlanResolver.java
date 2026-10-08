package com.example.ratelimiter.service;

import com.example.ratelimiter.model.RateLimitPlan;

/**
 * Decides which limits apply to a user.
 * <p>
 * The limiters never hardcode capacity/refill values; they ask this resolver. Supporting tiers
 * such as FREE (100, 10/s) and PREMIUM (1000, 100/s) only requires a new implementation,
 * e.g. one that looks up the user's subscription.
 */
public interface RateLimitPlanResolver {

    RateLimitPlan resolve(String userId);
}
