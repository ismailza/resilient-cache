/*
 * Copyright 2026 Ismail ZAHIR
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.ismailza.resilientcache;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.CacheErrorHandler;

/**
 * {@code FallThroughCacheErrorHandler} logs a failed cache operation and lets the request continue, so
 * the annotated method runs as if the entry were missing: a broken cache costs a database read, never a
 * failed request.
 *
 * <p>The trade-off: a failed eviction is swallowed too, so after an outage an entry may stay stale until
 * its TTL expires.</p>
 *
 * <p>Log lines carry the cache name and operation only. Spring's {@code LoggingCacheErrorHandler} also
 * prints the key, and a key may identify a person (an account id is personal data).</p>
 *
 * @author Ismail ZAHIR
 * @since 0.1.0
 */
public class FallThroughCacheErrorHandler implements CacheErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(FallThroughCacheErrorHandler.class);

    @Override
    public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
        this.log("get", exception, cache);
    }

    @Override
    public void handleCachePutError(RuntimeException exception, Cache cache, Object key, @Nullable Object value) {
        this.log("put", exception, cache);
    }

    @Override
    public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
        this.log("evict", exception, cache);
    }

    @Override
    public void handleCacheClearError(RuntimeException exception, Cache cache) {
        this.log("clear", exception, cache);
    }

    private void log(String operation, RuntimeException exception, Cache cache) {
        log.warn("Cache '{}' failed on {}; continuing without the cache: {}",
                cache.getName(), operation, CacheFailures.describe(exception));
    }
}
