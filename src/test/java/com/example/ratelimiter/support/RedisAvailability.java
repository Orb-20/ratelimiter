package com.example.ratelimiter.support;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Used with {@code @EnabledIf} so Redis integration tests run when a local Redis is up
 * and are reported as skipped (instead of failing) when it is not.
 */
public final class RedisAvailability {

    private static final String HOST = System.getProperty("redis.host", "localhost");
    private static final int PORT = Integer.getInteger("redis.port", 6379);
    private static final int TIMEOUT_MILLIS = 500;

    private RedisAvailability() {
    }

    public static boolean isRunning() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, PORT), TIMEOUT_MILLIS);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
