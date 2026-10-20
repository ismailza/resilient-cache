/*
 * Copyright 2026 Ismail ZAHIR
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.ismailza.resilientcache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots a real auto-configured application — the only way to go through the import filters, which
 * {@code ApplicationContextRunner} skips. No test connects to Redis: Lettuce connects lazily.
 */
class SpringDataRedisAutoConfigurationFilterTests {

    @Test
    @DisplayName("Should keep Spring Boot's Redis beans out of a Caffeine deployment")
    void caffeineDeploymentHasNoRedisBeans() {
        try (ConfigurableApplicationContext context = run()) {
            assertThat(context.getBeansOfType(RedisConnectionFactory.class)).isEmpty();
            assertThat(context.getBeansOfType(StringRedisTemplate.class)).isEmpty();
        }
    }

    @Test
    @DisplayName("Should own the Redis connection in a Redis deployment")
    void redisDeploymentUsesTheStarterConnection() {
        try (ConfigurableApplicationContext context = run(
                "resilient-cache.provider=redis", "resilient-cache.key-prefix=shop:test",
                "resilient-cache.redis.host=redis.invalid")) {
            assertThat(context.getBeansOfType(RedisConnectionFactory.class)).hasSize(1);
            assertThat(context.getBean(LettuceConnectionFactory.class).getHostName()).isEqualTo("redis.invalid");
            assertThat(context.getBeansOfType(StringRedisTemplate.class))
                    .as("Boot's templates stay out: the starter only serves the cache")
                    .isEmpty();
            assertThat(context.getBean(CacheManager.class)).isInstanceOf(FailoverCacheManager.class);
        }
    }

    @Test
    @DisplayName("Should let Spring Boot configure Redis when the filter is turned off")
    void filterCanBeTurnedOff() {
        try (ConfigurableApplicationContext context = run(
                SpringDataRedisAutoConfigurationFilter.ENABLED_PROPERTY + "=false")) {
            assertThat(context.getBeansOfType(RedisConnectionFactory.class)).hasSize(1);
            assertThat(context.getBeansOfType(StringRedisTemplate.class)).hasSize(1);
        }
    }

    private static ConfigurableApplicationContext run(String... properties) {
        return new SpringApplicationBuilder(Application.class)
                .web(WebApplicationType.NONE)
                .properties(properties)
                .run();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EnableCaching
    static class Application {
        @Bean
        CacheNamesProvider cacheNames() {
            return () -> List.of("countries");
        }
    }
}
