/*
 * Copyright 2026 Ismail ZAHIR
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.ismailza.resilientcache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.time.Duration;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FailoverCacheTests {
    private static final String KEY = "key";
    private static final String VALUE = "value";

    private Cache primary;

    private ConcurrentMapCache standby;
    private FailoverCache cache;

    @BeforeEach
    void setup() {
        this.primary = mock(Cache.class);
        when(this.primary.getName()).thenReturn("civilities");
        this.standby = new ConcurrentMapCache("civilities");
        this.cache = new FailoverCache(this.primary, this.standby);
    }

    @Test
    @DisplayName("Should serve get from the primary when it is healthy")
    void getServedByPrimaryWhenHealthy() {
        when(this.primary.get(KEY)).thenReturn(new Cache.ValueWrapper() {
            @Override
            public Object get() {
                return VALUE;
            }
        });

        Cache.ValueWrapper result = this.cache.get(KEY);

        assertThat(result).isNotNull();
        assertThat(result.get()).isEqualTo(VALUE);
    }

    @Test
    @DisplayName("Should serve get from the standby when the primary throws")
    void getFailsOverToStandby() {
        this.standby.put(KEY, VALUE);
        when(this.primary.get(KEY)).thenThrow(new RuntimeException("redis down"));

        Cache.ValueWrapper result = this.cache.get(KEY);

        assertThat(result).isNotNull();
        assertThat(result.get()).isEqualTo(VALUE);
    }

    @Test
    @DisplayName("Should mirror a successful primary put into the standby")
    void putMirrorsIntoStandby() {
        this.cache.put(KEY, VALUE);

        verify(this.primary).put(KEY, VALUE);
        assertThat(this.standby.get(KEY))
                .as("standby must hold the value so it is warm when an outage starts")
                .isNotNull()
                .extracting(Cache.ValueWrapper::get)
                .isEqualTo(VALUE);
    }

    @Test
    @DisplayName("Should still put into the standby when the primary put throws")
    void putFailsOverToStandby() {
        doThrow(new RuntimeException("redis down")).when(this.primary).put(KEY, VALUE);

        this.cache.put(KEY, VALUE);

        assertThat(this.standby.get(KEY)).isNotNull();
    }

    @Test
    @DisplayName("Should evict the standby even when the primary evict throws")
    void evictFailsOverToStandby() {
        this.standby.put(KEY, VALUE);
        doThrow(new RuntimeException("redis down")).when(this.primary).evict(KEY);

        this.cache.evict(KEY);

        assertThat(this.standby.get(KEY)).isNull();
    }

    @Test
    @DisplayName("Should clear both stores")
    void clearClearsBothStores() {
        this.standby.put(KEY, VALUE);

        this.cache.clear();

        verify(this.primary).clear();
        assertThat(this.standby.get(KEY)).isNull();
    }

    @Test
    @DisplayName("Should evict from both stores when the primary is healthy")
    void evictEvictsBothStores() {
        this.standby.put(KEY, VALUE);

        this.cache.evict(KEY);

        verify(this.primary).evict(KEY);
        assertThat(this.standby.get(KEY)).isNull();
    }

    @Test
    @DisplayName("Should rethrow a loader failure instead of failing over")
    void loaderFailureIsNotFailedOver() {
        Callable<String> loader = () -> VALUE;
        when(this.primary.get(any(), any(Callable.class)))
                .thenThrow(new Cache.ValueRetrievalException(KEY, loader, new IllegalStateException("db down")));

        assertThatThrownBy(() -> this.cache.get(KEY, loader))
                .as("the loader itself failed — the standby would only rerun and fail it again")
                .isInstanceOf(Cache.ValueRetrievalException.class);
    }

    @Test
    @DisplayName("Should run the loader against the standby when the primary store throws")
    void loaderGetFailsOverToStandby() {
        when(this.primary.get(any(), any(Callable.class))).thenThrow(new RuntimeException("redis down"));

        String result = this.cache.get(KEY, () -> VALUE);

        assertThat(result).isEqualTo(VALUE);
        assertThat(this.standby.get(KEY))
                .as("standby caches what the loader produced")
                .isNotNull();
    }

    @Test
    @DisplayName("Should expose the primary cache's name")
    void nameDelegatesToPrimary() {
        assertThat(this.cache.getName()).isEqualTo("civilities");
    }


    @Test
    @DisplayName("Should stop calling an unreachable primary and serve from the standby")
    void unreachablePrimaryIsLeftAlone() {
        FailoverCache cache = new FailoverCache(this.primary, this.standby,
                new PrimaryAvailability(Duration.ofMinutes(1)));
        this.standby.put(KEY, VALUE);
        when(this.primary.get(KEY)).thenThrow(new RedisConnectionFailureException("Unable to connect to redis:6379"));

        assertThat(cache.get(KEY)).isNotNull().extracting(Cache.ValueWrapper::get).isEqualTo(VALUE);
        assertThat(cache.get(KEY)).isNotNull().extracting(Cache.ValueWrapper::get).isEqualTo(VALUE);

        verify(this.primary, times(1)).get(KEY);
    }

    @Test
    @DisplayName("Should keep calling the primary after a failure of one entry")
    void entryFailureKeepsCallingThePrimary() {
        when(this.primary.get(KEY)).thenThrow(new IllegalStateException("bad entry"));

        this.cache.get(KEY);
        this.cache.get(KEY);

        verify(this.primary, times(2)).get(KEY);
    }

    @Test
    @DisplayName("Should mirror the value a loader call returns into the standby")
    void loaderValueIsMirrored() {
        when(this.primary.get(eq(KEY), any(Callable.class))).thenReturn(VALUE);

        assertThat(this.cache.get(KEY, () -> VALUE)).isEqualTo(VALUE);

        assertThat(this.standby.get(KEY))
                .as("a sync cache must be warm when an outage starts, as a put one is")
                .isNotNull()
                .extracting(Cache.ValueWrapper::get)
                .isEqualTo(VALUE);
    }
}
