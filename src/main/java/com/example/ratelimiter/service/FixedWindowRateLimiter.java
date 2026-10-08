package com.example.ratelimiter.service;

import com.example.ratelimiter.config.RateLimitProperties;
import com.example.ratelimiter.model.RateLimitAlgorithm;
import com.example.ratelimiter.model.RateLimitResponse;
import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Implementation 1: in-memory Fixed Window Counter.
 * <p>
 * Time is cut into fixed windows aligned to the clock (e.g. 10:00:00-10:00:59, 10:01:00-10:01:59).
 * Each user may make {@code maxRequests} requests per window.
 * <p>
 * Known limitation (boundary problem): a user can send {@code maxRequests} at 10:00:59 and another
 * {@code maxRequests} at 10:01:00 - twice the limit within one second.
 */
@Service
public class FixedWindowRateLimiter implements RateLimiterService {

    private static final Logger log = LoggerFactory.getLogger(FixedWindowRateLimiter.class);
    private static final long MILLIS_PER_SECOND = 1000L;

    private final ConcurrentMap<String, CounterState> counters = new ConcurrentHashMap<>();
    private final long maxRequests;
    private final long windowMillis;
    private final Clock clock;

    public FixedWindowRateLimiter(RateLimitProperties properties, Clock clock) {
        this.maxRequests = properties.counter().maxRequests();
        this.windowMillis = properties.counter().windowSeconds() * MILLIS_PER_SECOND;
        this.clock = clock;
    }

    @Override
    public RateLimitAlgorithm algorithm() {
        return RateLimitAlgorithm.FIXED_WINDOW;
    }

    @Override
    public RateLimitResponse tryConsume(String userId) {
        long now = clock.millis();
        long currentWindowStart = now - (now % windowMillis);

        // compute() runs atomically per key: the reset/increment and the read are one step.
        CounterState state = counters.compute(userId, (id, existing) ->
                existing == null || existing.windowStartTime() != currentWindowStart
                        ? new CounterState(currentWindowStart, 1)
                        : existing.increment());

        RateLimitResponse response = state.requestCount() <= maxRequests
                ? RateLimitResponse.allowed(algorithm(), maxRequests, maxRequests - state.requestCount())
                : RateLimitResponse.rejected(algorithm(), maxRequests, secondsUntilWindowEnds(currentWindowStart, now));

        log.info("user={} algorithm={} allowed={} remaining={}",
                userId, algorithm(), response.allowed(), response.remaining());
        return response;
    }

    private long secondsUntilWindowEnds(long windowStart, long now) {
        long millisLeft = windowStart + windowMillis - now;
        return Math.max(1, (millisLeft + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND);
    }

    /** Immutable per-user counter; replaced atomically inside {@link ConcurrentHashMap#compute}. */
    private record CounterState(long windowStartTime, long requestCount) {

        CounterState increment() {
            return new CounterState(windowStartTime, requestCount + 1);
        }
    }
}
