package com.example.ratelimiter.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Outcome of a rate-limit check.
 *
 * @param retryAfterSeconds only set for rejected requests
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RateLimitResponse(
        boolean allowed,
        RateLimitAlgorithm algorithm,
        long limit,
        long remaining,
        Long retryAfterSeconds) {

    public static RateLimitResponse allowed(RateLimitAlgorithm algorithm, long limit, long remaining) {
        return new RateLimitResponse(true, algorithm, limit, remaining, null);
    }

    public static RateLimitResponse rejected(RateLimitAlgorithm algorithm, long limit, long retryAfterSeconds) {
        return new RateLimitResponse(false, algorithm, limit, 0, retryAfterSeconds);
    }
}
