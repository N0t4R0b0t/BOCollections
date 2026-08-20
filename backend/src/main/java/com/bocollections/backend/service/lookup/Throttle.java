package com.bocollections.backend.service.lookup;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Enforces a minimum interval between calls by sleeping only the *remaining* gap since the last
 * call, not a fixed delay every time. Each external service needing this (MusicBrainz, UPCitemdb)
 * has its own rate limit, so each holds its own instance rather than sharing one.
 */
class Throttle {

    private final AtomicLong lastCallMs = new AtomicLong(0);
    private final long minIntervalMs;

    Throttle(long minIntervalMs) {
        this.minIntervalMs = minIntervalMs;
    }

    void await() throws InterruptedException {
        long now = System.currentTimeMillis();
        long last = lastCallMs.get();
        long wait = minIntervalMs - (now - last);
        if (wait > 0) Thread.sleep(wait);
        lastCallMs.set(System.currentTimeMillis());
    }
}
