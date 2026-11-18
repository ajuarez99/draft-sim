package com.ballknowers.draftsim.usage;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

/** A settable clock for the usage tests: the limiter's windows and the report's "today" both read it. */
public final class MutableClock extends Clock {

    private final AtomicReference<Instant> now;
    private final ZoneId zone;

    public MutableClock(Instant start, ZoneId zone) {
        this.now = new AtomicReference<>(start);
        this.zone = zone;
    }

    public void set(Instant instant) {
        now.set(instant);
    }

    public void advance(java.time.Duration d) {
        now.updateAndGet(i -> i.plus(d));
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId z) {
        // a view shares the instant source, so it keeps following set()/advance()
        return new Clock() {
            @Override
            public ZoneId getZone() {
                return z;
            }

            @Override
            public Clock withZone(ZoneId other) {
                return MutableClock.this.withZone(other);
            }

            @Override
            public Instant instant() {
                return MutableClock.this.instant();
            }
        };
    }

    @Override
    public Instant instant() {
        return now.get();
    }
}
