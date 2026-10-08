package com.example.ratelimiter.exception;

import com.example.ratelimiter.model.RateLimitResponse;

/** Thrown when a request is rejected by a rate limiter; mapped to HTTP 429. */
public class RateLimitExceededException extends RuntimeException {

    private final transient RateLimitResponse response;

    public RateLimitExceededException(RateLimitResponse response) {
        super("Rate limit exceeded for algorithm " + response.algorithm());
        this.response = response;
    }

    public RateLimitResponse getResponse() {
        return response;
    }
}
