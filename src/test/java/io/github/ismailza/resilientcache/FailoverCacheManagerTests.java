/*
 * Copyright 2026 Ismail ZAHIR
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.ismailza.resilientcache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FailoverCacheManagerTests {

    private static final String COUNTRIES = "countries";
    private static final String LANGUAGES = "languages";
    private static final Set<String> NAMES = Set.of(COUNTRIES, LANGUAGES);

    private FailoverCacheManager manager;

    @BeforeEach
    void setup() {
        this.manager = new FailoverCacheManager(
                CacheManagerFactory.caffeine(NAMES, Duration.ofMinutes(5), 100),
                CacheManagerFactory.caffeine(NAMES, Duration.ofMinutes(1), 100));
    }

    @Test
    @DisplayName("Should wrap every registered cache in a FailoverCache")
    void registeredCacheIsWrapped() {
        Cache cache = this.manager.getCache(COUNTRIES);

        assertThat(cache)
                .isInstanceOf(FailoverCache.class)
                .extracting(Cache::getName)
                .isEqualTo(COUNTRIES);
    }

    @Test
    @DisplayName("Should return null for a cache name that is not registered")
    void unregisteredCacheIsNull() {
        assertThat(this.manager.getCache("not-a-registered-cache"))
                .as("the fail-fast contract of the fixed cache-name set must survive wrapping")
                .isNull();
    }

    @Test
    @DisplayName("Should expose every registered name")
    void cacheNamesMatchRegistry() {
        assertThat(this.manager.getCacheNames())
                .containsExactlyInAnyOrderElementsOf(NAMES);
    }

    @Test
    @DisplayName("Should return the same wrapper instance on repeated lookups")
    void cacheInstanceIsReused() {
        Cache first = this.manager.getCache(COUNTRIES);
        Cache second = this.manager.getCache(COUNTRIES);

        assertThat(first).isSameAs(second);
    }


    @Test
    @DisplayName("Should stop calling the primary for every cache once one found it unreachable")
    void cachesShareTheAvailability() {
        Cache countries = mock(Cache.class);
        Cache languages = mock(Cache.class);
        CacheManager primary = mock(CacheManager.class);
        when(primary.getCache(COUNTRIES)).thenReturn(countries);
        when(primary.getCache(LANGUAGES)).thenReturn(languages);
        when(countries.getName()).thenReturn(COUNTRIES);
        when(countries.get("key")).thenThrow(new RedisConnectionFailureException("Unable to connect to redis:6379"));
        FailoverCacheManager failover = new FailoverCacheManager(primary,
                CacheManagerFactory.caffeine(NAMES, Duration.ofMinutes(1), 100),
                new PrimaryAvailability(Duration.ofMinutes(1)));

        Objects.requireNonNull(failover.getCache(COUNTRIES)).get("key");
        Objects.requireNonNull(failover.getCache(LANGUAGES)).get("key");

        verify(languages, never()).get("key");
    }
}
