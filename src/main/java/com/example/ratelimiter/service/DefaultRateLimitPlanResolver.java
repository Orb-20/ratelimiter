package com.example.ratelimiter.service;

import com.example.ratelimiter.config.RateLimitProperties;
import com.example.ratelimiter.model.RateLimitPlan;
import org.springframework.stereotype.Component;

/** Gives every user the plan configured under {@code rate-limit.token-bucket.*}. */
@Component
public class DefaultRateLimitPlanResolver implements RateLimitPlanResolver {

    private final RateLimitPlan defaultPlan;

    public DefaultRateLimitPlanResolver(RateLimitProperties properties) {
        RateLimitProperties.TokenBucket tokenBucket = properties.tokenBucket();
        this.defaultPlan = new RateLimitPlan(tokenBucket.capacity(), tokenBucket.refillRate());
    }

    @Override
    public RateLimitPlan resolve(String userId) {
        return defaultPlan;
    }
}
