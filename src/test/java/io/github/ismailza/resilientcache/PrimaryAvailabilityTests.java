/*
 * Copyright 2026 Ismail ZAHIR
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.ismailza.resilientcache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrimaryAvailabilityTests {

    private static final Duration RETRY_INTERVAL = Duration.ofSeconds(5);

    private final AtomicLong clock = new AtomicLong();
    private final PrimaryAvailability availability = new PrimaryAvailability(RETRY_INTERVAL, this.clock::get);

    @Test
    @DisplayName("Should try the primary while it is up")
    void triesThePrimaryWhileUp() {
        assertThat(this.availability.shouldTry()).isTrue();
        assertThat(this.availability.shouldTry()).isTrue();
    }

    @Test
    @DisplayName("Should leave an unreachable primary alone until the retry interval has passed")
    void leavesAnUnreachablePrimaryAlone() {
        assertThat(this.availability.failed("countries", "get", new RedisConnectionFailureException("refused")))
                .isTrue();

        assertThat(this.availability.shouldTry()).isFalse();
        this.advance(RETRY_INTERVAL.minusMillis(1));
        assertThat(this.availability.shouldTry()).isFalse();
    }

    @Test
    @DisplayName("Should let a single operation retry the primary once the interval has passed")
    void oneOperationRetries() {
        this.availability.failed("countries", "get", new QueryTimeoutException("timed out"));
        this.advance(RETRY_INTERVAL);

        assertThat(this.availability.shouldTry()).as("the first operation retries").isTrue();
        assertThat(this.availability.shouldTry()).as("the others stay on the standby meanwhile").isFalse();
    }

    @Test
    @DisplayName("Should try the primary on every operation again once it served one")
    void recoversAfterASuccess() {
        this.availability.failed("countries", "get", new RedisConnectionFailureException("refused"));
        this.advance(RETRY_INTERVAL);
        this.availability.shouldTry();

        this.availability.succeeded();

        assertThat(this.availability.shouldTry()).isTrue();
        assertThat(this.availability.shouldTry()).isTrue();
    }

    @Test
    @DisplayName("Should not mark the primary down for a failure of one entry")
    void entryFailureKeepsThePrimaryUp() {
        assertThat(this.availability.failed("countries", "get", new IllegalStateException("bad entry"))).isFalse();

        assertThat(this.availability.shouldTry()).isTrue();
    }

    @Test
    @DisplayName("Should refuse a retry interval that is not positive")
    void refusesANonPositiveInterval() {
        assertThatThrownBy(() -> new PrimaryAvailability(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void advance(Duration duration) {
        this.clock.addAndGet(duration.toNanos());
    }
}
