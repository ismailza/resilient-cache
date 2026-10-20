/*
 * Copyright 2026 Ismail ZAHIR
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.ismailza.resilientcache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * {@code PrimaryAvailability} remembers that the primary store is unreachable, so the caches sharing it
 * stop trying it for a while.
 *
 * <p>Without it, every cache operation during an outage would wait for the command timeout before failing
 * over, and log a warning: a request reading three caches would be six seconds slower. Once the primary
 * is marked down, operations go straight to the standby until {@code retryInterval} has passed; then one
 * operation tries the primary again while the others stay on the standby. The outage is logged when it
 * starts, once per failed retry while it lasts, and when it ends.</p>
 *
 * <p>Only an unreachable store marks it down ({@link CacheFailures#isUnavailability}): one entry that
 * fails to read must not take Redis away from every cache.</p>
 *
 * @author Ismail ZAHIR
 * @since 0.1.0
 */
final class PrimaryAvailability {

    /** How long the primary is left alone after it was found unreachable. */
    static final Duration DEFAULT_RETRY_INTERVAL = Duration.ofSeconds(5);

    private static final Logger log = LoggerFactory.getLogger(PrimaryAvailability.class);

    private final long retryIntervalNanos;
    private final LongSupplier nanoClock;
    private final AtomicBoolean down = new AtomicBoolean();
    private final AtomicLong retryAtNanos = new AtomicLong();

    PrimaryAvailability(Duration retryInterval) {
        this(retryInterval, System::nanoTime);
    }

    PrimaryAvailability(Duration retryInterval, LongSupplier nanoClock) {
        if (retryInterval.isNegative() || retryInterval.isZero()) {
            throw new IllegalArgumentException("The primary retry interval must be positive: " + retryInterval);
        }
        this.retryIntervalNanos = retryInterval.toNanos();
        this.nanoClock = nanoClock;
    }

    /**
     * Whether an operation should try the primary: it is up, or its retry interval has passed and no other
     * operation has claimed the retry.
     */
    boolean shouldTry() {
        if (!this.down.get()) {
            return true;
        }
        long now = this.nanoClock.getAsLong();
        long retryAt = this.retryAtNanos.get();
        return now - retryAt >= 0 && this.retryAtNanos.compareAndSet(retryAt, now + this.retryIntervalNanos);
    }

    /** Records an operation the primary served. */
    void succeeded() {
        if (this.down.get() && this.down.compareAndSet(true, false)) {
            log.info("Cache primary store is reachable again; leaving the in-memory standby");
        }
    }

    /**
     * Records that the primary failed {@code operation} on {@code cacheName}.
     *
     * @return whether the failure marked the primary down — the caller logs any other failure itself
     */
    boolean failed(String cacheName, String operation, RuntimeException exception) {
        if (!CacheFailures.isUnavailability(exception)) {
            return false;
        }
        this.retryAtNanos.set(this.nanoClock.getAsLong() + this.retryIntervalNanos);
        boolean outageStarts = this.down.compareAndSet(false, true);
        log.warn("Cache primary store {} on {} of '{}'; serving every cache from the in-memory standby, "
                        + "retrying in {}: {}", outageStarts ? "is unreachable" : "is still unreachable", operation,
                cacheName, Duration.ofNanos(this.retryIntervalNanos), CacheFailures.describe(exception));
        return true;
    }
}
