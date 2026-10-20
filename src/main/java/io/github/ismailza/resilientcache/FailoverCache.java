/*
 * Copyright 2026 Ismail ZAHIR
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.ismailza.resilientcache;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;

import java.util.concurrent.Callable;

/**
 * {@code FailoverCache} decorates a primary (Redis) cache with an in-memory standby.
 *
 * <p>Every operation is attempted on the primary first; when the primary store throws —
 * an unreachable Redis, a timeout, a serialization defect — the operation is served by
 * the local standby instead, and the request never notices. Successful primary puts, and
 * values the primary returns to a loader call ({@code @Cacheable(sync = true)}), are
 * mirrored into the standby so it is warm when an outage starts. A plain read is not
 * mirrored: an entry another instance wrote is only in the standby once this instance
 * writes it too.</p>
 *
 * <p>An unreachable primary is left alone for a while ({@link PrimaryAvailability}), so an
 * outage does not cost every operation a command timeout.</p>
 *
 * <p>While the primary is down, instances cannot see each other's writes or evictions:
 * the standby is per-instance, so its entries must live shorter than the primary's
 * ({@code resilient-cache.fallback-time-to-live}). Writes and evictions made during the
 * outage never reach the primary, so once it is back it may serve entries that were
 * replaced or evicted meanwhile, until their TTL expires. Failure log lines carry the
 * cache name and operation only — never the key or value, which may identify a person.</p>
 *
 * @author Ismail ZAHIR
 * @since 0.1.0
 * @see FailoverCacheManager
 */
public class FailoverCache implements Cache {

    private static final Logger log = LoggerFactory.getLogger(FailoverCache.class);

    private final Cache primary;
    private final Cache standby;
    private final PrimaryAvailability availability;

    public FailoverCache(Cache primary, Cache standby) {
        this(primary, standby, new PrimaryAvailability(PrimaryAvailability.DEFAULT_RETRY_INTERVAL));
    }

    FailoverCache(Cache primary, Cache standby, PrimaryAvailability availability) {
        this.primary = primary;
        this.standby = standby;
        this.availability = availability;
    }

    @Override
    public String getName() {
        return this.primary.getName();
    }

    @Override
    public Object getNativeCache() {
        return this.primary.getNativeCache();
    }

    @Override
    public @Nullable ValueWrapper get(Object key) {
        if (this.availability.shouldTry()) {
            try {
                ValueWrapper value = this.primary.get(key);
                this.availability.succeeded();
                return value;
            } catch (RuntimeException exception) {
                this.failedOver("get", exception);
            }
        }
        return this.standby.get(key);
    }

    @Override
    public <T> @Nullable T get(Object key, @Nullable Class<T> type) {
        if (this.availability.shouldTry()) {
            try {
                T value = this.primary.get(key, type);
                this.availability.succeeded();
                return value;
            } catch (RuntimeException exception) {
                this.failedOver("get", exception);
            }
        }
        return this.standby.get(key, type);
    }

    @Override
    public <T> @Nullable T get(Object key, Callable<T> valueLoader) {
        if (this.availability.shouldTry()) {
            try {
                T value = this.primary.get(key, valueLoader);
                this.availability.succeeded();
                this.standby.put(key, value);
                return value;
            } catch (ValueRetrievalException exception) {
                // The loader itself failed, not the cache store — the standby would only
                // rerun and fail it again.
                throw exception;
            } catch (RuntimeException exception) {
                this.failedOver("get", exception);
            }
        }
        return this.standby.get(key, valueLoader);
    }

    @Override
    public void put(Object key, @Nullable Object value) {
        if (this.availability.shouldTry()) {
            try {
                this.primary.put(key, value);
                this.availability.succeeded();
            } catch (RuntimeException exception) {
                this.failedOver("put", exception);
            }
        }
        this.standby.put(key, value);
    }

    @Override
    public void evict(Object key) {
        this.standby.evict(key);
        if (this.availability.shouldTry()) {
            try {
                this.primary.evict(key);
                this.availability.succeeded();
            } catch (RuntimeException exception) {
                this.failedOver("evict", exception);
            }
        }
    }

    @Override
    public void clear() {
        this.standby.clear();
        if (this.availability.shouldTry()) {
            try {
                this.primary.clear();
                this.availability.succeeded();
            } catch (RuntimeException exception) {
                this.failedOver("clear", exception);
            }
        }
    }

    private void failedOver(String operation, RuntimeException exception) {
        // The availability logs an unreachable store; any other failure is logged here.
        if (!this.availability.failed(this.getName(), operation, exception)) {
            log.warn("Cache '{}' primary store failed on {}; continuing on in-memory standby: {}",
                    this.getName(), operation, CacheFailures.describe(exception));
        }
    }
}
