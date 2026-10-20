/*
 * Copyright 2026 Ismail ZAHIR
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.ismailza.resilientcache;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code ResilientCacheProperties} binds the {@code resilient-cache.*} properties.
 *
 * <p>The single switch is {@link #provider()}: {@code caffeine} (the default) runs an in-memory cache
 * with no external infrastructure, while {@code redis} shares one cache across the instances of an
 * application.</p>
 *
 * @param provider            the cache provider to use
 * @param timeToLive          expiration applied to every cache entry, e.g. {@code 1h}
 * @param fallbackTimeToLive  expiration for the in-memory standby that serves while Redis is
 *                            unreachable; kept short because standby entries are per-instance and
 *                            cannot see other instances' evictions
 * @param maximumSize         maximum number of entries per cache; enforced by Caffeine only — Redis
 *                            relies on the entry TTL and its own memory policy
 * @param timeToLiveOverrides per-cache TTL overrides keyed by cache name, for caches where the default
 *                            is unacceptable (granted permissions, for one). Every key must be a
 *                            registered cache name — an unknown name fails at startup
 * @param keyPrefix           the namespace of this application's Redis keys, required with the
 *                            {@code redis} provider; see {@link #redisKeyPrefix()}
 * @param redis               Redis connection settings, only read with the {@code redis} provider
 *
 * @author Ismail ZAHIR
 * @since 0.1.0
 */
@ConfigurationProperties(prefix = "resilient-cache")
public record ResilientCacheProperties(
        @DefaultValue("caffeine") Provider provider,
        @DefaultValue("1h") Duration timeToLive,
        @DefaultValue("5m") Duration fallbackTimeToLive,
        @DefaultValue("10000") long maximumSize,
        Map<String, Duration> timeToLiveOverrides,
        @Nullable String keyPrefix,
        @DefaultValue Redis redis
) {

    /** Separator between the segments of a Redis key. */
    public static final String KEY_SEPARATOR = ":";

    // Null arrives despite the non-null types: the binder passes it for unset overrides, and Java callers may.
    @SuppressWarnings({"ConstantValue", "DataFlowIssue"})
    public ResilientCacheProperties {
        timeToLiveOverrides = timeToLiveOverrides == null ? Map.of() : Map.copyOf(timeToLiveOverrides);
        redis = redis == null ? Redis.defaults() : redis;
        // Zero means "never expire" to Redis but "expire at once" to Caffeine: refuse it rather than pick one.
        requirePositive(timeToLive, "resilient-cache.time-to-live");
        requirePositive(fallbackTimeToLive, "resilient-cache.fallback-time-to-live");
        timeToLiveOverrides.forEach((cacheName, override) ->
                requirePositive(override, "resilient-cache.time-to-live-overrides." + cacheName));
        if (maximumSize <= 0) {
            throw new IllegalArgumentException("resilient-cache.maximum-size must be positive: " + maximumSize);
        }
    }

    private static void requirePositive(Duration duration, String property) {
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException(property + " must be positive: " + duration);
        }
    }

    /**
     * The prefix of every Redis key this application writes: {@link #keyPrefix()} in lower case, ending in
     * {@code :}. A key reads {@code <prefix><cacheName>:<key>}, e.g.
     * {@code shop:catalog:prod:products:42}.
     *
     * <p>Name the application and every dimension whose data must never mix — environment, tenant or
     * region: {@code <application>:<environment>}. Two applications, or two deployments, sharing one
     * Redis instance must not read each other's entries.</p>
     *
     * @return the normalized prefix
     * @throws IllegalStateException when no prefix is configured
     */
    public String redisKeyPrefix() {
        if (!StringUtils.hasText(this.keyPrefix)) {
            throw new IllegalStateException("resilient-cache.key-prefix is required with the redis provider, "
                    + "e.g. <application>:<environment>, so that applications sharing a Redis instance never "
                    + "read each other's entries");
        }
        String prefix = this.keyPrefix.trim().toLowerCase(Locale.ROOT);
        return prefix.endsWith(KEY_SEPARATOR) ? prefix : prefix + KEY_SEPARATOR;
    }

    /**
     * Supported cache providers.
     */
    public enum Provider {
        /** In-memory, per-instance cache. No infrastructure required. */
        CAFFEINE,
        /** Shared cache over a Redis instance. Required for multi-instance deployments. */
        REDIS
    }

    /**
     * Connection settings for the Redis provider. They live here rather than under
     * {@code spring.data.redis.*} because this starter, not Spring Boot, owns the Redis connection.
     *
     * @param host                          the Redis server host
     * @param port                          the Redis server port
     * @param password                      the Redis password; blank means no authentication
     * @param database                      the Redis logical database index
     * @param timeout                       the command timeout, e.g. {@code 2s}
     * @param replaceBootAutoConfiguration  leave out Spring Boot's Redis auto-configuration, so a
     *                                      Caffeine deployment opens no Redis connection; set it to
     *                                      {@code false} only in an application that uses Redis for more
     *                                      than its cache and configures {@code spring.data.redis.*}
     * @param trustedPackages               the packages whose classes a cached value may hold, besides
     *                                      JDK value types. A Redis entry naming any other class is
     *                                      refused, so whoever can write to Redis cannot make the
     *                                      application instantiate a class of their choosing. Empty
     *                                      means the application's own package: the one of its
     *                                      {@code @SpringBootApplication} class
     * @param ssl                           connect over TLS; required when Redis is reached over a
     *                                      network the application does not own, since the password and
     *                                      every cached value cross it
     */
    public record Redis(
            @DefaultValue("localhost") String host,
            @DefaultValue("6379") int port,
            @Nullable String password,
            @DefaultValue("0") int database,
            @DefaultValue("2s") Duration timeout,
            @DefaultValue("true") boolean replaceBootAutoConfiguration,
            List<String> trustedPackages,
            @DefaultValue("false") boolean ssl
    ) {

        /**
         * Packages that may never be trusted as a whole: their classes include ones that open files or
         * connections, or run code, when Jackson constructs them.
         */
        static final List<String> UNTRUSTABLE_PACKAGES = List.of("java", "javax", "jdk", "sun", "com.sun");

        // Null arrives for an unset trusted-packages, and from a Java caller.
        @SuppressWarnings("ConstantValue")
        public Redis {
            trustedPackages = trustedPackages == null ? List.of() : List.copyOf(trustedPackages);
            for (String trustedPackage : trustedPackages) {
                if (!StringUtils.hasText(trustedPackage)) {
                    throw new IllegalArgumentException("resilient-cache.redis.trusted-packages may not hold a blank "
                            + "entry: it would trust every class");
                }
                String name = trustedPackage.trim();
                if (UNTRUSTABLE_PACKAGES.stream().anyMatch(jdk -> name.equals(jdk) || name.startsWith(jdk + "."))) {
                    throw new IllegalArgumentException("resilient-cache.redis.trusted-packages may not hold the JDK "
                            + "package '" + name + "': JDK value types are already accepted, and the rest of the "
                            + "JDK holds classes that open files or connections when constructed");
                }
            }
            requirePositive(timeout, "resilient-cache.redis.timeout");
        }

        /** The settings used when the {@code redis} section is absent. */
        static Redis defaults() {
            return new Redis("localhost", 6379, null, 0, Duration.ofSeconds(2), true, List.of(), false);
        }

        /** Masks the password: a record would print it with every other field. */
        @Override
        public String toString() {
            return "Redis[host=%s, port=%d, password=%s, database=%d, timeout=%s, replaceBootAutoConfiguration=%s, "
                    .formatted(this.host, this.port, StringUtils.hasText(this.password) ? "******" : "<none>",
                            this.database, this.timeout, this.replaceBootAutoConfiguration)
                    + "trustedPackages=%s, ssl=%s]".formatted(this.trustedPackages, this.ssl);
        }
    }
}
