/*
 * Copyright 2026 Ismail ZAHIR
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.ismailza.resilientcache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.list;
import static org.mockito.Mockito.mock;

class CacheManagerFactoryTests {

    private static final String PERMISSIONS = "permissions";
    private static final String COUNTRIES = "countries";
    private static final Set<String> NAMES = Set.of(PERMISSIONS, COUNTRIES);
    /** This package: the test's cached classes live in it. */
    private static final List<String> TRUSTED = List.of("io.github.ismailza.resilientcache");

    @Test
    @DisplayName("Should create exactly the registered caches for the Caffeine provider")
    void caffeineManagerKnowsOnlyRegisteredCaches() {
        CacheManager manager = CacheManagerFactory.caffeine(NAMES, Duration.ofMinutes(5), 100);

        assertThat(manager.getCacheNames()).containsExactlyInAnyOrderElementsOf(NAMES);
        assertThat(manager.getCache(COUNTRIES)).isNotNull();
        assertThat(manager.getCache("not-a-registered-cache")).isNull();
    }

    @Test
    @DisplayName("Should create exactly the registered caches for the Redis provider")
    void redisManagerKnowsOnlyRegisteredCaches() {
        RedisCacheManager manager = redisManager(properties(Map.of()));

        assertThat(manager.getCacheNames()).containsExactlyInAnyOrderElementsOf(NAMES);
        assertThat(manager.getCache("not-a-registered-cache")).isNull();
    }

    @Test
    @DisplayName("Should prefix Redis keys with the configured namespace")
    void redisKeysCarryThePrefix() {
        String prefix = configurationOf(COUNTRIES).getKeyPrefixFor(COUNTRIES);

        assertThat(prefix)
                .as("applications and deployments sharing a Redis instance must never mix entries")
                .isEqualTo("shop:test:countries:");
    }

    @Test
    @DisplayName("Should refuse to build a Redis manager without a key prefix")
    void redisManagerRequiresAKeyPrefix() {
        ResilientCacheProperties withoutPrefix = new ResilientCacheProperties(ResilientCacheProperties.Provider.REDIS, Duration.ofHours(1),
                Duration.ofMinutes(5), 100, Map.of(), " ", redis());

        assertThatThrownBy(() -> CacheManagerFactory.redis(NAMES, withoutPrefix, mock(RedisConnectionFactory.class),
                TRUSTED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("resilient-cache.key-prefix");
    }

    @Test
    @DisplayName("Should refuse to build a Redis manager that trusts no package")
    void redisManagerRequiresTrustedPackages() {
        assertThatThrownBy(() -> CacheManagerFactory.redis(NAMES, properties(Map.of()),
                mock(RedisConnectionFactory.class), List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("resilient-cache.redis.trusted-packages");
    }

    @Test
    @DisplayName("Should round-trip an object with Instant fields and a derived getter through the Redis value serializer")
    void redisValueSerializerRoundTripsAuditedObject() {
        // Instant audit fields and a derived getter with no setter: the two shapes that broke the
        // default serializer configuration on JPA entities.
        Audited audited = new Audited();
        audited.setId(UUID.randomUUID());
        audited.setCreatedAt(Instant.parse("2026-07-23T10:15:30.123456Z"));
        RedisSerializationContext.SerializationPair<Object> valuePair =
                configurationOf(COUNTRIES).getValueSerializationPair();

        ByteBuffer written = valuePair.write(new ArrayList<>(List.of(audited)));
        Object read = valuePair.read(written);

        assertThat(read).asInstanceOf(list(Audited.class))
                .singleElement()
                .satisfies(restored -> {
                    assertThat(restored.getId()).isEqualTo(audited.getId());
                    assertThat(restored.getCreatedAt()).isEqualTo(audited.getCreatedAt());
                });
    }

    @Test
    @DisplayName("Should round-trip a list of UUIDs through the Redis value serializer")
    void redisValueSerializerRoundTripsUuidList() {
        List<UUID> keys = new ArrayList<>(List.of(UUID.randomUUID(), UUID.randomUUID()));
        RedisSerializationContext.SerializationPair<Object> valuePair =
                configurationOf(PERMISSIONS).getValueSerializationPair();

        assertThat(valuePair.read(valuePair.write(keys))).isEqualTo(keys);
    }

    @Test
    @DisplayName("Should apply a TTL override to its cache only")
    void ttlOverrideAppliesPerCache() {
        RedisCacheManager manager = redisManager(properties(Map.of(PERMISSIONS, Duration.ofMinutes(2))));

        assertThat(configurationOf(manager, PERMISSIONS).getTtlFunction()
                .getTimeToLive(new Object(), null))
                .isEqualTo(Duration.ofMinutes(2));
        assertThat(configurationOf(manager, COUNTRIES).getTtlFunction()
                .getTimeToLive(new Object(), null))
                .as("caches without an override keep the default TTL")
                .isEqualTo(Duration.ofHours(1));
    }

    @Test
    @DisplayName("Should reject a TTL override for a cache name that is not registered")
    void unknownOverrideNameFailsAtStartup() {
        assertThatThrownBy(() -> CacheManagerFactory.caffeine(NAMES, Duration.ofHours(1), 100,
                Map.of("not-a-registered-cache", Duration.ofMinutes(2))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("resilient-cache.time-to-live-overrides")
                .hasMessageContaining("not-a-registered-cache");
    }

    @Test
    @DisplayName("Should wrap the Redis manager with a standby that knows the same caches")
    void redisWithStandbyWrapsEveryCache() {
        CacheManager manager = CacheManagerFactory.redisWithStandby(NAMES, properties(Map.of()),
                mock(RedisConnectionFactory.class), TRUSTED);

        assertThat(manager).isInstanceOf(FailoverCacheManager.class);
        assertThat(manager.getCacheNames()).containsExactlyInAnyOrderElementsOf(NAMES);
        assertThat(manager.getCache(PERMISSIONS)).isInstanceOf(FailoverCache.class);
    }

    @Test
    @DisplayName("Should cap the standby's TTL overrides at the fallback TTL")
    void standbyOverridesAreCappedAtTheFallbackTtl() {
        // The fallback TTL is 5 minutes: permissions must still expire after 1 while Redis is down,
        // and countries, which Redis keeps for 2 hours, only for 5 minutes on the standby.
        ResilientCacheProperties properties = properties(Map.of(
                PERMISSIONS, Duration.ofMinutes(1),
                COUNTRIES, Duration.ofHours(2)));

        assertThat(CacheManagerFactory.standbyTimeToLiveOverrides(properties)).containsExactlyInAnyOrderEntriesOf(Map.of(
                PERMISSIONS, Duration.ofMinutes(1),
                COUNTRIES, Duration.ofMinutes(5)));
    }

    @Test
    @DisplayName("Should cap the standby's default TTL at the Redis TTL")
    void standbyDefaultIsCappedAtTheTtl() {
        ResilientCacheProperties shortTtl = new ResilientCacheProperties(ResilientCacheProperties.Provider.REDIS, Duration.ofMinutes(1),
                Duration.ofMinutes(5), 100, Map.of(), "shop:Test", redis());

        assertThat(CacheManagerFactory.standbyTimeToLive(shortTtl))
                .as("an entry must not live longer because Redis is down")
                .isEqualTo(Duration.ofMinutes(1));
        assertThat(CacheManagerFactory.standbyTimeToLive(properties(Map.of())))
                .as("the fallback TTL applies when it is the shorter")
                .isEqualTo(Duration.ofMinutes(5));
    }

    private static ResilientCacheProperties properties(Map<String, Duration> overrides) {
        return new ResilientCacheProperties(ResilientCacheProperties.Provider.REDIS, Duration.ofHours(1), Duration.ofMinutes(5),
                100, overrides, "shop:Test", redis());
    }

    private static ResilientCacheProperties.Redis redis() {
        return new ResilientCacheProperties.Redis("localhost", 6379, "", 0, Duration.ofSeconds(2), true,
                List.of(), false);
    }

    private static RedisCacheManager redisManager(ResilientCacheProperties properties) {
        return (RedisCacheManager) CacheManagerFactory.redis(NAMES, properties, mock(RedisConnectionFactory.class), TRUSTED);
    }

    private static RedisCacheConfiguration configurationOf(String cacheName) {
        return configurationOf(redisManager(properties(Map.of())), cacheName);
    }

    private static RedisCacheConfiguration configurationOf(RedisCacheManager manager, String cacheName) {
        return Objects.requireNonNull(manager.getCacheConfigurations().get(cacheName),
                () -> "no configuration for cache " + cacheName);
    }

    /** The shape of an audited JPA entity: Instant fields and a derived getter. */
    public static class Audited {

        private UUID id;
        private Instant createdAt;

        public UUID getId() {
            return this.id;
        }

        public void setId(UUID id) {
            this.id = id;
        }

        public Instant getCreatedAt() {
            return this.createdAt;
        }

        public void setCreatedAt(Instant createdAt) {
            this.createdAt = createdAt;
        }

        @SuppressWarnings("unused") // read by Jackson: the derived property must round-trip
        public boolean isNew() {
            return this.createdAt == null;
        }
    }
}
