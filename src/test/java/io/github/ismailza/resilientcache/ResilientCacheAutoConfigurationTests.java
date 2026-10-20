/*
 * Copyright 2026 Ismail ZAHIR
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.ismailza.resilientcache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ResilientCacheAutoConfigurationTests {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ResilientCacheAutoConfiguration.class));

    @Test
    @DisplayName("Should create a Caffeine manager with the declared caches by default")
    void caffeineByDefault() {
        this.runner.withUserConfiguration(CountriesCache.class).run(context -> {
            assertThat(context).hasSingleBean(CacheManager.class).doesNotHaveBean(RedisConnectionFactory.class);
            assertThat(context.getBean(CacheManager.class))
                    .isInstanceOf(CaffeineCacheManager.class)
                    .satisfies(manager -> assertThat(manager.getCacheNames()).containsExactly("countries"));
        });
    }

    @Test
    @DisplayName("Should create the union of every provider's caches")
    void unionOfProviders() {
        this.runner.withUserConfiguration(CountriesCache.class, PermissionsCache.class).run(context ->
                assertThat(context.getBean(CacheManager.class).getCacheNames())
                        .containsExactlyInAnyOrder("countries", "permissions"));
    }

    @Test
    @DisplayName("Should fail at startup when no cache name is declared")
    void failsWithoutCacheNames() {
        this.runner.run(context -> assertThat(context)
                .hasFailed()
                .getFailure()
                .rootCause()
                .hasMessageContaining("CacheNamesProvider"));
    }

    @Test
    @DisplayName("Should fail at startup when a cache name is blank")
    void failsOnABlankCacheName() {
        this.runner.withBean(CacheNamesProvider.class, () -> () -> List.of("countries", " "))
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("blank cache name"));
    }

    @Test
    @DisplayName("Should bind per-cache TTL overrides and reject one for an unknown cache")
    void bindsOverrides() {
        this.runner.withUserConfiguration(PermissionsCache.class)
                .withPropertyValues("resilient-cache.time-to-live-overrides.permissions=2m")
                .run(context -> assertThat(context.getBean(ResilientCacheProperties.class).timeToLiveOverrides())
                        .containsEntry("permissions", Duration.ofMinutes(2)));
        this.runner.withUserConfiguration(PermissionsCache.class)
                .withPropertyValues("resilient-cache.time-to-live-overrides.unknown=2m")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("unknown"));
    }

    @Test
    @DisplayName("Should create a Redis manager with an in-memory standby for the redis provider")
    void redisProvider() {
        this.runner.withUserConfiguration(CountriesCache.class)
                .withPropertyValues("resilient-cache.provider=redis", "resilient-cache.key-prefix=shop:test",
                        "resilient-cache.redis.trusted-packages=com.example.shop")
                .run(context -> {
                    assertThat(context).hasSingleBean(RedisConnectionFactory.class);
                    assertThat(context.getBean(CacheManager.class))
                            .isInstanceOf(FailoverCacheManager.class)
                            .satisfies(manager -> assertThat(manager.getCacheNames()).containsExactly("countries"));
                });
    }

    @Test
    @DisplayName("Should connect to Redis over TLS when asked")
    void redisOverTls() {
        this.runner.withUserConfiguration(CountriesCache.class)
                .withPropertyValues("resilient-cache.provider=redis", "resilient-cache.key-prefix=shop:test",
                        "resilient-cache.redis.trusted-packages=com.example.shop", "resilient-cache.redis.ssl=true")
                .run(context -> assertThat(context.getBean(LettuceConnectionFactory.class).isUseSsl())
                        .isTrue());
    }

    @Test
    @DisplayName("Should refuse the redis provider without a key prefix")
    void redisRequiresAKeyPrefix() {
        this.runner.withUserConfiguration(CountriesCache.class)
                .withPropertyValues("resilient-cache.provider=redis")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("resilient-cache.key-prefix"));
    }

    @Test
    @DisplayName("Should refuse the redis provider when no package is trusted and the application's is unknown")
    void redisRequiresTrustedPackages() {
        this.runner.withUserConfiguration(CountriesCache.class)
                .withPropertyValues("resilient-cache.provider=redis", "resilient-cache.key-prefix=shop:test")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("resilient-cache.redis.trusted-packages"));
    }

    @Test
    @DisplayName("Should trust the application's package unless trusted packages are configured")
    void trustsTheApplicationPackageByDefault() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        AutoConfigurationPackages.register(beanFactory, "com.example.shop", "");

        assertThat(ResilientCacheAutoConfiguration.RedisCacheConfiguration.trustedPackages(
                properties(List.of()), beanFactory))
                .as("the default package is never trusted as a blank entry")
                .containsExactly("com.example.shop");
        assertThat(ResilientCacheAutoConfiguration.RedisCacheConfiguration.trustedPackages(
                properties(List.of("com.example.model")), beanFactory))
                .containsExactly("com.example.model");
        assertThat(ResilientCacheAutoConfiguration.RedisCacheConfiguration.trustedPackages(
                properties(List.of()), new DefaultListableBeanFactory()))
                .isEmpty();
    }

    private static ResilientCacheProperties properties(List<String> trustedPackages) {
        return new ResilientCacheProperties(ResilientCacheProperties.Provider.REDIS, Duration.ofHours(1),
                Duration.ofMinutes(5), 100, Map.of(), "shop:test", new ResilientCacheProperties.Redis("localhost",
                6379, null, 0, Duration.ofSeconds(2), true, trustedPackages, false));
    }

    @Test
    @DisplayName("Should let cache errors fall through to the annotated method")
    void registersTheFallThroughErrorHandler() {
        this.runner.withUserConfiguration(CountriesCache.class).run(context ->
                assertThat(context.getBean(CachingConfigurer.class).errorHandler())
                        .isInstanceOf(FallThroughCacheErrorHandler.class));
    }

    @Test
    @DisplayName("Should back off when the application defines its own cache manager")
    void backsOffForAnApplicationManager() {
        this.runner.withUserConfiguration(CountriesCache.class, OwnManager.class).run(context ->
                assertThat(context.getBean(CacheManager.class)).isInstanceOf(ConcurrentMapCacheManager.class));
    }

    @Configuration(proxyBeanMethods = false)
    static class CountriesCache {
        @Bean
        CacheNamesProvider countries() {
            return () -> List.of("countries");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class PermissionsCache {
        @Bean
        CacheNamesProvider permissions() {
            return () -> List.of("permissions");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class OwnManager {
        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager("countries");
        }
    }
}
