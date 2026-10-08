package com.example.ratelimiter.controller;

import com.example.ratelimiter.config.RateLimitProperties;
import com.example.ratelimiter.exception.InvalidUserIdException;
import com.example.ratelimiter.exception.RateLimitExceededException;
import com.example.ratelimiter.model.RateLimitAlgorithm;
import com.example.ratelimiter.model.RateLimitResponse;
import com.example.ratelimiter.service.RateLimiterService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rate-limit")
public class RateLimiterController {

    public static final String USER_ID_HEADER = "X-User-Id";
    /** Identifies which instance answered - handy when demonstrating several servers. */
    public static final String SERVER_PORT_HEADER = "X-Server-Port";

    /** Restricts user ids to safe characters: keeps Redis keys and log lines clean. */
    private static final Pattern VALID_USER_ID = Pattern.compile("[A-Za-z0-9_.:@-]{1,64}");

    private final Map<RateLimitAlgorithm, RateLimiterService> limiters = new EnumMap<>(RateLimitAlgorithm.class);
    private final RateLimitAlgorithm defaultAlgorithm;

    public RateLimiterController(List<RateLimiterService> services, RateLimitProperties properties) {
        services.forEach(service -> limiters.put(service.algorithm(), service));
        this.defaultAlgorithm = properties.defaultAlgorithm();
    }

    /** Uses the algorithm configured by {@code rate-limit.default-algorithm}. */
    @GetMapping("/test")
    public ResponseEntity<RateLimitResponse> test(@RequestHeader(USER_ID_HEADER) String userId,
                                                  HttpServletRequest request) {
        return check(defaultAlgorithm, userId, request);
    }

    @GetMapping("/counter")
    public ResponseEntity<RateLimitResponse> counter(@RequestHeader(USER_ID_HEADER) String userId,
                                                     HttpServletRequest request) {
        return check(RateLimitAlgorithm.FIXED_WINDOW, userId, request);
    }

    @GetMapping("/token-bucket")
    public ResponseEntity<RateLimitResponse> tokenBucket(@RequestHeader(USER_ID_HEADER) String userId,
                                                         HttpServletRequest request) {
        return check(RateLimitAlgorithm.TOKEN_BUCKET, userId, request);
    }

    @GetMapping("/redis")
    public ResponseEntity<RateLimitResponse> redis(@RequestHeader(USER_ID_HEADER) String userId,
                                                   HttpServletRequest request) {
        return check(RateLimitAlgorithm.REDIS_TOKEN_BUCKET, userId, request);
    }

    private ResponseEntity<RateLimitResponse> check(RateLimitAlgorithm algorithm, String userId,
                                                    HttpServletRequest request) {
        validate(userId);
        RateLimitResponse response = limiters.get(algorithm).tryConsume(userId);
        if (!response.allowed()) {
            throw new RateLimitExceededException(response);
        }
        return ResponseEntity.ok()
                .headers(RateLimitHeaders.from(response))
                .header(SERVER_PORT_HEADER, String.valueOf(request.getLocalPort()))
                .body(response);
    }

    private static void validate(String userId) {
        if (!VALID_USER_ID.matcher(userId).matches()) {
            throw new InvalidUserIdException(
                    USER_ID_HEADER + " must be 1-64 characters of letters, digits or _ . : @ -");
        }
    }
}
