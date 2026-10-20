/*
 * Copyright 2026 Ismail ZAHIR
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.ismailza.resilientcache;

import java.util.Collection;

/**
 * {@code CacheNamesProvider} declares caches an application may use.
 *
 * <p>The cache manager knows exactly the names its providers declare, so a {@code @Cacheable} naming
 * anything else fails at first use instead of silently creating an unbounded cache. An application declares
 * one bean, usually from a constants interface its {@code @Cacheable} annotations also read:</p>
 *
 * <pre>{@code
 * @Bean
 * CacheNamesProvider cacheNames() {
 *     return () -> CacheNames.ALL;
 * }
 * }</pre>
 *
 * <p>Several beans may contribute: the cache manager holds the union of their names.</p>
 *
 * @author Ismail ZAHIR
 * @since 0.1.0
 */
@FunctionalInterface
public interface CacheNamesProvider {

    /**
     * @return the names of the caches to create
     */
    Collection<String> cacheNames();
}
