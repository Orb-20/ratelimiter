package com.example.ratelimiter.controller;

import com.example.ratelimiter.model.RateLimitResponse;
import org.springframework.http.HttpHeaders;

/** Builds the standard rate-limit response headers. */
public final class RateLimitHeaders {

    public static final String LIMIT = "X-RateLimit-Limit";
    public static final String REMAINING = "X-RateLimit-Remaining";

    private RateLimitHeaders() {
    }

    public static HttpHeaders from(RateLimitResponse response) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(LIMIT, String.valueOf(response.limit()));
        headers.add(REMAINING, String.valueOf(response.remaining()));
        if (response.retryAfterSeconds() != null) {
            headers.add(HttpHeaders.RETRY_AFTER, String.valueOf(response.retryAfterSeconds()));
        }
        return headers;
    }
}
