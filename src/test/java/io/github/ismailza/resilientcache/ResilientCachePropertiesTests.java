/*
 * Copyright 2026 Ismail ZAHIR
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.ismailza.resilientcache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResilientCachePropertiesTests {

    @Test
    @DisplayName("Should normalize the key prefix to lower case ending in a separator")
    void normalizesTheKeyPrefix() {
        assertThat(withPrefix("shop:Catalog:PROD").redisKeyPrefix()).isEqualTo("shop:catalog:prod:");
        assertThat(withPrefix(" shop:catalog:prod: ").redisKeyPrefix()).isEqualTo("shop:catalog:prod:");
    }

    @Test
    @DisplayName("Should default every setting but the key prefix")
    void defaults() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ResilientCacheAutoConfiguration.class))
                .withBean(CacheNamesProvider.class, () -> () -> List.of("countries"))
                .run(context -> {
                    ResilientCacheProperties properties = context.getBean(ResilientCacheProperties.class);
                    assertThat(properties.provider()).isEqualTo(ResilientCacheProperties.Provider.CAFFEINE);
                    assertThat(properties.timeToLive()).isEqualTo(Duration.ofHours(1));
                    assertThat(properties.fallbackTimeToLive()).isEqualTo(Duration.ofMinutes(5));
                    assertThat(properties.maximumSize()).isEqualTo(10_000);
                    assertThat(properties.timeToLiveOverrides()).isEmpty();
                    assertThat(properties.keyPrefix()).isNull();
                    assertThat(properties.redis().host()).isEqualTo("localhost");
                    assertThat(properties.redis().port()).isEqualTo(6379);
                    assertThat(properties.redis().timeout()).isEqualTo(Duration.ofSeconds(2));
                    assertThat(properties.redis().replaceBootAutoConfiguration()).isTrue();
                    assertThat(properties.redis().trustedPackages())
                            .as("empty: the application package is trusted").isEmpty();
                    assertThat(properties.redis().ssl()).isFalse();
                });
    }

    @Test
    @DisplayName("Should never print the Redis password")
    void masksThePassword() {
        ResilientCacheProperties.Redis redis = new ResilientCacheProperties.Redis("redis", 6379, "s3cr3t-value", 0,
                Duration.ofSeconds(2), true, List.of("com.example.shop"), false);

        assertThat(redis.toString()).doesNotContain("s3cr3t-value").contains("password=******");
        assertThat(withPrefix("shop:test").toString()).doesNotContain("s3cr3t-value");
    }

    @Test
    @DisplayName("Should refuse a blank trusted package, which would trust every class")
    void refusesABlankTrustedPackage() {
        assertThatThrownBy(() -> new ResilientCacheProperties.Redis("redis", 6379, null, 0,
                        Duration.ofSeconds(2), true, List.of("com.example.shop", " "), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trusted-packages");
    }

    @Test
    @DisplayName("Should refuse to trust a JDK package, whose classes may open files or connections")
    void refusesAJdkTrustedPackage() {
        for (String jdkPackage : List.of("java", "java.net", " java.util.logging ", "javax.naming", "jdk.internal",
                "sun.misc", "com.sun.rowset")) {
            assertThatThrownBy(() -> new ResilientCacheProperties.Redis("redis", 6379, null, 0,
                    Duration.ofSeconds(2), true, List.of(jdkPackage), false))
                    .as(jdkPackage)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("trusted-packages");
        }
        assertThat(new ResilientCacheProperties.Redis("redis", 6379, null, 0, Duration.ofSeconds(2), true,
                List.of("javafoo.model", "com.sunrise"), false).trustedPackages())
                .as("only the JDK packages themselves, not names that merely start alike")
                .containsExactly("javafoo.model", "com.sunrise");
    }

    @Test
    @DisplayName("Should default the collections and Redis settings a Java caller leaves null")
    @SuppressWarnings("DataFlowIssue") // passes null on purpose, against the declared types
    void defaultsWhatAJavaCallerLeavesNull() {
        ResilientCacheProperties properties = new ResilientCacheProperties(ResilientCacheProperties.Provider.CAFFEINE, Duration.ofHours(1),
                Duration.ofMinutes(5), 100, null, null, null);
        ResilientCacheProperties.Redis redis = new ResilientCacheProperties.Redis("redis", 6379, null, 0, Duration.ofSeconds(2), true,
                null, false);

        assertThat(properties.timeToLiveOverrides()).isEmpty();
        assertThat(properties.redis().trustedPackages()).isEmpty();
        assertThat(redis.trustedPackages()).isEmpty();
    }

    @Test
    @DisplayName("Should refuse a TTL, size or timeout that is not positive, naming the property")
    void refusesNonPositiveSettings() {
        assertThatThrownBy(() -> settings(Duration.ZERO, Duration.ofMinutes(5), 100, Map.of()))
                .hasMessageContaining("resilient-cache.time-to-live ");
        assertThatThrownBy(() -> settings(Duration.ofHours(1), Duration.ofMinutes(-1), 100, Map.of()))
                .hasMessageContaining("resilient-cache.fallback-time-to-live");
        assertThatThrownBy(() -> settings(Duration.ofHours(1), Duration.ofMinutes(5), 0, Map.of()))
                .hasMessageContaining("resilient-cache.maximum-size");
        assertThatThrownBy(() -> settings(Duration.ofHours(1), Duration.ofMinutes(5), 100,
                Map.of("permissions", Duration.ZERO)))
                .hasMessageContaining("resilient-cache.time-to-live-overrides.permissions");
        assertThatThrownBy(() -> new ResilientCacheProperties.Redis("redis", 6379, null, 0, Duration.ZERO, true,
                List.of(), false))
                .hasMessageContaining("resilient-cache.redis.timeout");
    }

    @Test
    @DisplayName("Should fail at startup on a zero TTL, which Redis would read as never expire")
    void zeroTtlFailsAtStartup() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ResilientCacheAutoConfiguration.class))
                .withBean(CacheNamesProvider.class, () -> () -> List.of("countries"))
                .withPropertyValues("resilient-cache.time-to-live=0")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("resilient-cache.time-to-live"));
    }

    private static ResilientCacheProperties settings(Duration timeToLive, Duration fallbackTimeToLive, long maximumSize,
                                            Map<String, Duration> overrides) {
        return new ResilientCacheProperties(ResilientCacheProperties.Provider.CAFFEINE, timeToLive, fallbackTimeToLive, maximumSize,
                overrides, null, ResilientCacheProperties.Redis.defaults());
    }

    private static ResilientCacheProperties withPrefix(String prefix) {
        return new ResilientCacheProperties(ResilientCacheProperties.Provider.REDIS, Duration.ofHours(1), Duration.ofMinutes(5), 100,
                Map.of(), prefix, ResilientCacheProperties.Redis.defaults());
    }
}
