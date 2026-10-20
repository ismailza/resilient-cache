/*
 * Copyright 2026 Ismail ZAHIR
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.ismailza.resilientcache;

import org.jspecify.annotations.Nullable;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code FailoverCacheManager} pairs each primary (Redis) cache with its in-memory
 * standby, wrapping them in a {@link FailoverCache}.
 *
 * <p>Both delegate managers are built from the same fixed set of cache names, so a name
 * unknown to the primary is unknown here too — the fail-fast contract of the cache setup
 * is preserved.</p>
 *
 * <p>Its caches share one {@link PrimaryAvailability}: they all sit on the same Redis, so once one
 * finds it unreachable, the others stop waiting on it too.</p>
 *
 * @author Ismail ZAHIR
 * @since 0.1.0
 */
public class FailoverCacheManager implements CacheManager {

    private final CacheManager primary;
    private final CacheManager standby;
    private final PrimaryAvailability availability;
    private final Map<String, Cache> caches = new ConcurrentHashMap<>();

    public FailoverCacheManager(CacheManager primary, CacheManager standby) {
        this(primary, standby, new PrimaryAvailability(PrimaryAvailability.DEFAULT_RETRY_INTERVAL));
    }

    FailoverCacheManager(CacheManager primary, CacheManager standby, PrimaryAvailability availability) {
        this.primary = primary;
        this.standby = standby;
        this.availability = availability;
    }

    @Override
    public @Nullable Cache getCache(String name) {
        Cache wrapped = this.caches.get(name);
        if (wrapped != null) {
            return wrapped;
        }
        Cache primaryCache = this.primary.getCache(name);
        if (primaryCache == null) {
            return null;
        }
        return this.caches.computeIfAbsent(name, cacheName -> new FailoverCache(
                primaryCache,
                Objects.requireNonNull(this.standby.getCache(cacheName),
                        () -> "Standby cache manager does not know cache '%s'".formatted(cacheName)),
                this.availability));
    }

    @Override
    public Collection<String> getCacheNames() {
        return this.primary.getCacheNames();
    }
}
