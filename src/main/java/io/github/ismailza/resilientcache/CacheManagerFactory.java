/*
 * Copyright 2026 Ismail ZAHIR
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.ismailza.resilientcache;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.cache.support.NullValue;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import tools.jackson.databind.jsontype.PolymorphicTypeValidator;

import java.time.Duration;
import java.util.Collection;
import java.util.Currency;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * {@code CacheManagerFactory} builds the {@link CacheManager} for each supported provider.
 *
 * <p>Every manager shares the same contract: entries expire after the default TTL — or a per-cache
 * override, for caches where the default is unacceptable. Only the given cache names exist: an
 * unregistered name fails at first use instead of silently creating an unbounded cache, and an
 * override for an unknown name fails at startup.</p>
 *
 * <p>The auto-configuration builds its managers here; tests build the same managers directly.</p>
 *
 * @author Ismail ZAHIR
 * @since 0.1.0
 */
public final class CacheManagerFactory {

    /** The {@code java.util} types other than collections and maps a cached value may hold. */
    private static final Set<Class<?>> JDK_UTIL_VALUES = Set.of(UUID.class, Date.class, Locale.class, Currency.class);

    private CacheManagerFactory() {
    }

    /**
     * The in-memory Caffeine manager configured by {@code resilient-cache.*}.
     *
     * @param cacheNames the caches to create
     * @param properties the bound properties
     * @return a Caffeine-backed cache manager
     */
    public static CacheManager caffeine(Set<String> cacheNames, ResilientCacheProperties properties) {
        return caffeine(cacheNames, properties.timeToLive(), properties.maximumSize(),
                properties.timeToLiveOverrides());
    }

    /**
     * A Caffeine manager with one TTL for every cache.
     */
    public static CacheManager caffeine(Set<String> cacheNames, Duration timeToLive, long maximumSize) {
        return caffeine(cacheNames, timeToLive, maximumSize, Map.of());
    }

    /**
     * A Caffeine manager: per-entry TTL with per-cache overrides, bounded size, and hit/miss statistics
     * enabled for observability.
     */
    public static CacheManager caffeine(Set<String> cacheNames, Duration timeToLive, long maximumSize,
                                        Map<String, Duration> timeToLiveOverrides) {
        validateOverrides(cacheNames, timeToLiveOverrides);
        CaffeineCacheManager cacheManager = new CaffeineCacheManager();
        cacheManager.setCaffeine(caffeineSpec(timeToLive, maximumSize));
        cacheManager.setCacheNames(cacheNames);
        timeToLiveOverrides.forEach((cacheName, ttl) -> cacheManager.registerCustomCache(cacheName,
                caffeineSpec(ttl, maximumSize).build()));
        return cacheManager;
    }

    private static Caffeine<Object, Object> caffeineSpec(Duration timeToLive, long maximumSize) {
        return Caffeine.newBuilder()
                .expireAfterWrite(timeToLive)
                .maximumSize(maximumSize)
                .recordStats();
    }

    /**
     * The Redis manager wrapped in a {@link FailoverCacheManager}: when Redis is unreachable, requests are
     * served from an in-memory standby that keeps entries for {@code fallback-time-to-live}. A shorter
     * {@code time-to-live}, or a shorter per-cache override, bounds the standby too — permissions, say, may
     * not live longer just because Redis is down.
     *
     * @param cacheNames        the caches to create
     * @param properties        the bound properties
     * @param connectionFactory the Redis connection
     * @param trustedPackages   the packages whose classes a cached value may hold; see
     *                          {@link #redisValueSerializer(Collection)}
     * @return the failover manager
     */
    public static CacheManager redisWithStandby(Set<String> cacheNames, ResilientCacheProperties properties,
                                                RedisConnectionFactory connectionFactory,
                                                Collection<String> trustedPackages) {
        CacheManager redis = redis(cacheNames, properties, connectionFactory, trustedPackages);
        CacheManager standby = caffeine(cacheNames, standbyTimeToLive(properties), properties.maximumSize(),
                standbyTimeToLiveOverrides(properties));
        return new FailoverCacheManager(redis, standby);
    }

    /**
     * The standby's TTL for caches without an override: {@code fallback-time-to-live}, capped at
     * {@code time-to-live} so no entry outlives the TTL it has in Redis.
     */
    static Duration standbyTimeToLive(ResilientCacheProperties properties) {
        return shorter(properties.fallbackTimeToLive(), properties.timeToLive());
    }

    /**
     * The standby's per-cache TTLs: each override capped at {@code fallback-time-to-live}.
     */
    static Map<String, Duration> standbyTimeToLiveOverrides(ResilientCacheProperties properties) {
        Duration fallback = properties.fallbackTimeToLive();
        return properties.timeToLiveOverrides().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> shorter(entry.getValue(), fallback)));
    }

    private static Duration shorter(Duration first, Duration second) {
        return first.compareTo(second) <= 0 ? first : second;
    }

    /**
     * The bare Redis manager. Keys are plain strings prefixed with {@code <keyPrefix><cacheName>:}
     * ({@link ResilientCacheProperties#redisKeyPrefix()}), so entries are readable in {@code redis-cli} and never
     * mix between applications or deployments. Values are JSON with embedded type information, so
     * cached classes need not implement {@code Serializable}; see {@link #redisValueSerializer(Collection)}.
     *
     * @param cacheNames        the caches to create
     * @param properties        the bound properties
     * @param connectionFactory the Redis connection
     * @param trustedPackages   the packages whose classes a cached value may hold
     * @return a Redis-backed cache manager, its caches already created
     * @throws IllegalStateException when no trusted package is given
     */
    public static CacheManager redis(Set<String> cacheNames, ResilientCacheProperties properties,
                                     RedisConnectionFactory connectionFactory, Collection<String> trustedPackages) {
        validateOverrides(cacheNames, properties.timeToLiveOverrides());
        String keyPrefix = properties.redisKeyPrefix();
        if (trustedPackages.isEmpty()) {
            throw new IllegalStateException("resilient-cache.redis.trusted-packages is required with the redis "
                    + "provider when the application package cannot be detected: list the packages of the "
                    + "classes this application caches");
        }
        RedisCacheConfiguration defaults = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(properties.timeToLive())
                .computePrefixWith(cacheName -> keyPrefix + cacheName + ResilientCacheProperties.KEY_SEPARATOR)
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(redisValueSerializer(trustedPackages)));
        Map<String, RedisCacheConfiguration> initialConfigurations = new HashMap<>();
        cacheNames.forEach(cacheName -> initialConfigurations.put(cacheName,
                defaults.entryTtl(properties.timeToLiveOverrides().getOrDefault(cacheName, properties.timeToLive()))));
        RedisCacheManager cacheManager = RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaults)
                .withInitialCacheConfigurations(initialConfigurations)
                .disableCreateOnMissingCache()
                .build();
        // The manager is wrapped by FailoverCacheManager instead of being exposed as a
        // bean, so Spring never triggers its afterPropertiesSet-based cache creation.
        cacheManager.initializeCaches();
        return cacheManager;
    }

    /**
     * The serializer of Redis cache values: JSON with default typing. Jackson 3 handles {@code java.time}
     * values out of the box. Unknown properties are ignored on read — the JSON also captures derived
     * getters with no settable counterpart, and entries written by an older version of an application must
     * not break a newer one.
     *
     * <p>Every entry names the class it is read back as, so whoever can write to Redis would choose what
     * the application instantiates — the classic Jackson deserialization gadget. Only the trusted packages and
     * a closed list of JDK value types may be named ({@link #valueTypeValidator}); an entry naming anything
     * else fails to read, and the cache falls through to the method.</p>
     *
     * <p>Default typing cannot rebuild every type — final JDK collections ({@code List.of()},
     * {@code Stream.toList()}) are written without type information and fail at read time. An application
     * should round-trip each type it caches through this serializer in a test.</p>
     *
     * @param trustedPackages the packages whose classes may be cached, e.g. {@code com.example.shop}
     * @return the value serializer
     */
    public static GenericJacksonJsonRedisSerializer redisValueSerializer(Collection<String> trustedPackages) {
        return GenericJacksonJsonRedisSerializer.builder()
                .enableDefaultTyping(valueTypeValidator(trustedPackages))
                .enableSpringCacheNullValueSupport()
                .customize(mapper -> mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES))
                .build();
    }

    /**
     * Which classes a Redis entry may name: those of the trusted packages (and their sub-packages), arrays,
     * Spring's cached {@code null}, and the JDK value types reference data is made of. Matching is by exact
     * package, not prefix: {@code java.util.logging} or {@code java.net} classes, whose constructors open
     * files or connections, are never allowed.
     */
    static PolymorphicTypeValidator valueTypeValidator(Collection<String> trustedPackages) {
        BasicPolymorphicTypeValidator.Builder validator = BasicPolymorphicTypeValidator.builder()
                .allowIfSubTypeIsArray()
                .allowIfSubType(NullValue.class)
                .allowIfSubType((context, type) -> isJdkValue(type));
        trustedPackages.forEach(trustedPackage -> validator.allowIfSubType(
                trustedPackage.endsWith(".") ? trustedPackage : trustedPackage + "."));
        return validator.build();
    }

    private static boolean isJdkValue(Class<?> type) {
        return switch (type.getPackageName()) {
            case "java.lang" -> type == String.class || type == Boolean.class || type == Character.class
                    || Number.class.isAssignableFrom(type);
            case "java.math" -> Number.class.isAssignableFrom(type);
            case "java.time" -> true;
            case "java.util" -> Collection.class.isAssignableFrom(type) || Map.class.isAssignableFrom(type)
                    || JDK_UTIL_VALUES.contains(type);
            default -> false;
        };
    }

    private static void validateOverrides(Set<String> cacheNames, Map<String, Duration> timeToLiveOverrides) {
        Set<String> unknown = new HashSet<>(timeToLiveOverrides.keySet());
        unknown.removeAll(cacheNames);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("resilient-cache.time-to-live-overrides names caches that are not "
                    + "registered by a CacheNamesProvider: " + unknown);
        }
    }
}
