package com.example.ratelimiter.service;

import com.example.ratelimiter.model.RateLimitAlgorithm;
import com.example.ratelimiter.model.RateLimitResponse;

/** Common contract of all rate-limiting implementations. */
public interface RateLimiterService {

    RateLimitAlgorithm algorithm();

    /**
     * Checks the user's limit and, if allowed, records the request.
     * The check and the update happen as one atomic step.
     */
    RateLimitResponse tryConsume(String userId);
}
