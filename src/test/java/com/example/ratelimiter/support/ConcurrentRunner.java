package com.example.ratelimiter.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Fires many requests at the same instant from a thread pool and counts how many were allowed. */
public final class ConcurrentRunner {

    private static final long TIMEOUT_SECONDS = 30;

    private ConcurrentRunner() {
    }

    public static int countAllowed(int threads, int requests, BooleanSupplier request) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                results.add(pool.submit(() -> {
                    startGate.await();
                    return request.getAsBoolean();
                }));
            }
            startGate.countDown();

            int allowed = 0;
            for (Future<Boolean> result : results) {
                if (result.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    allowed++;
                }
            }
            return allowed;
        } finally {
            pool.shutdownNow();
        }
    }
}
