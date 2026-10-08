package com.example.ratelimiter.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * HTTP behaviour of the in-memory endpoints. Redis is pointed at an unused port, which also
 * verifies the fail-closed policy end to end without needing a Redis server.
 */
@SpringBootTest(properties = {
        "rate-limit.counter.max-requests=3",
        "rate-limit.counter.window-seconds=60",
        "rate-limit.token-bucket.capacity=3",
        "rate-limit.token-bucket.refill-rate=0.01",
        "rate-limit.redis.fail-open=false",
        "spring.data.redis.port=1",
        "spring.data.redis.connect-timeout=500ms",
        "spring.data.redis.timeout=500ms"
})
@AutoConfigureMockMvc
class RateLimiterControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void counterAllowsUpToLimitThenReturns429() throws Exception {
        String user = "counter-user";
        call("/api/rate-limit/counter", user)
                .andExpect(status().isOk())
                .andExpect(header().string(RateLimitHeaders.LIMIT, "3"))
                .andExpect(header().string(RateLimitHeaders.REMAINING, "2"))
                .andExpect(jsonPath("$.allowed").value(true))
                .andExpect(jsonPath("$.algorithm").value("FIXED_WINDOW"))
                .andExpect(jsonPath("$.remaining").value(2));
        call("/api/rate-limit/counter", user).andExpect(status().isOk());
        call("/api/rate-limit/counter", user).andExpect(status().isOk());

        call("/api/rate-limit/counter", user)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(RateLimitHeaders.LIMIT, "3"))
                .andExpect(header().string(RateLimitHeaders.REMAINING, "0"))
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.allowed").value(false));
    }

    @Test
    void tokenBucketReturns429WhenEmpty() throws Exception {
        String user = "bucket-user";
        for (int i = 0; i < 3; i++) {
            call("/api/rate-limit/token-bucket", user)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.algorithm").value("TOKEN_BUCKET"));
        }

        call("/api/rate-limit/token-bucket", user)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(RateLimitHeaders.REMAINING, "0"))
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    void testEndpointUsesDefaultAlgorithm() throws Exception {
        call("/api/rate-limit/test", "default-user")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.algorithm").value("TOKEN_BUCKET"))
                .andExpect(jsonPath("$.remaining").value(2));
    }

    @Test
    void redisEndpointFailsClosedWhenRedisIsUnreachable() throws Exception {
        call("/api/rate-limit/redis", "redis-down-user")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.algorithm").value("REDIS_TOKEN_BUCKET"));
    }

    @Test
    void missingUserHeaderReturns400() throws Exception {
        mockMvc.perform(get("/api/rate-limit/token-bucket")).andExpect(status().isBadRequest());
    }

    @Test
    void invalidUserIdReturns400() throws Exception {
        call("/api/rate-limit/token-bucket", "bad user id!").andExpect(status().isBadRequest());
    }

    private ResultActions call(String path, String userId) throws Exception {
        return mockMvc.perform(get(path).header(RateLimiterController.USER_ID_HEADER, userId));
    }
}
