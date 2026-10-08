package com.example.ratelimiter.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

/** A clock that only moves when a test tells it to. */
public class MutableClock extends Clock {

    private final AtomicLong millis;

    public MutableClock(Instant start) {
        this.millis = new AtomicLong(start.toEpochMilli());
    }

    public void advance(Duration duration) {
        millis.addAndGet(duration.toMillis());
    }

    public void setInstant(Instant instant) {
        millis.set(instant.toEpochMilli());
    }

    @Override
    public long millis() {
        return millis.get();
    }

    @Override
    public Instant instant() {
        return Instant.ofEpochMilli(millis());
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
