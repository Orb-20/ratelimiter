package com.example.ratelimiter.model;

public enum RateLimitAlgorithm {
    FIXED_WINDOW,
    TOKEN_BUCKET,
    REDIS_TOKEN_BUCKET
}
