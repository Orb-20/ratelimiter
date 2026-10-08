package com.example.ratelimiter.config;

import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

/**
 * Redis health check based on PING.
 * <p>
 * Registered as {@code redisHealthIndicator}, which replaces Spring Boot's default INFO-based check.
 * The default one fails on Windows Redis builds, whose INFO output contains backslash paths.
 */
@Component("redisHealthIndicator")
public class RedisPingHealthIndicator extends AbstractHealthIndicator {

    private final RedisConnectionFactory connectionFactory;

    public RedisPingHealthIndicator(RedisConnectionFactory connectionFactory) {
        super("Redis health check failed");
        this.connectionFactory = connectionFactory;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            builder.up().withDetail("ping", connection.ping());
        }
    }
}
