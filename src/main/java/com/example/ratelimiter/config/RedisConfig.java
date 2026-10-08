package com.example.ratelimiter.config;

import java.time.Clock;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.RedisScript;

@Configuration
public class RedisConfig {

    private static final String TOKEN_BUCKET_SCRIPT = "scripts/token_bucket.lua";

    /**
     * Lua script executing refill + check + consume atomically inside Redis.
     * Spring sends it with EVALSHA (falling back to EVAL), so the script body is not resent on every call.
     */
    @Bean
    @SuppressWarnings({"rawtypes", "unchecked"})
    public RedisScript<List<Long>> tokenBucketScript() {
        return (RedisScript) RedisScript.of(new ClassPathResource(TOKEN_BUCKET_SCRIPT), List.class);
    }

    /** Clock used by the in-memory limiters; tests replace it with a controllable clock. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
