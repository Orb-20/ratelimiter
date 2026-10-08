package com.example.ratelimiter.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** End-to-end 429 behaviour of GET /api/rate-limit/redis against the local Redis. */
@SpringBootTest(properties = {
        "rate-limit.token-bucket.capacity=3",
        "rate-limit.token-bucket.refill-rate=0.01"
})
@AutoConfigureMockMvc
@EnabledIf("com.example.ratelimiter.support.RedisAvailability#isRunning")
class RedisEndpointIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private StringRedisTemplate redisTemplate;

    private final String user = "it-http-" + UUID.randomUUID();

    @AfterEach
    void cleanUp() {
        redisTemplate.delete("rate-limit:token-bucket:" + user);
    }

    @Test
    void redisBucketReturns429WhenEmpty() throws Exception {
        for (int remaining = 2; remaining >= 0; remaining--) {
            mockMvc.perform(get("/api/rate-limit/redis").header(RateLimiterController.USER_ID_HEADER, user))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.algorithm").value("REDIS_TOKEN_BUCKET"))
                    .andExpect(header().string(RateLimitHeaders.REMAINING, String.valueOf(remaining)));
        }

        mockMvc.perform(get("/api/rate-limit/redis").header(RateLimiterController.USER_ID_HEADER, user))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(RateLimitHeaders.LIMIT, "3"))
                .andExpect(header().string(RateLimitHeaders.REMAINING, "0"))
                .andExpect(header().exists("Retry-After"));
    }
}
