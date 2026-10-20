/*
 * Copyright 2026 Ismail ZAHIR
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.ismailza.resilientcache;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@code ResilientCacheAutoConfiguration} creates the application's {@link CacheManager} from
 * {@code resilient-cache.*}: an in-memory Caffeine cache by default, or Redis with an in-memory standby
 * when {@code resilient-cache.provider} is {@code redis}.
 *
 * <p>The caches are exactly the names declared by the application's {@link CacheNamesProvider} beans; an
 * application that declares none fails at startup. Cache errors never fail a request
 * ({@link FallThroughCacheErrorHandler}).</p>
 *
 * <p>The application still enables caching itself with {@code @EnableCaching}; this starter only supplies
 * the manager, which the application may also inject to use the {@code Cache} API directly.</p>
 *
 * <p>An application that declares its own {@link CachingConfigurer} — for a key generator, say — replaces the
 * starter's, and with it the fall-through error handler: its configurer must return a
 * {@link FallThroughCacheErrorHandler} from {@code errorHandler()}, or cache errors fail requests again.</p>
 *
 * <p>This starter is the only source of Redis beans: Spring Boot's Redis auto-configuration is left
 * out ({@link SpringDataRedisAutoConfigurationFilter}), so a Caffeine deployment needs no Redis at all.
 * With the {@code redis} provider, Actuator's Redis health indicator appears because a
 * {@link RedisConnectionFactory} bean exists.</p>
 *
 * @author Ismail ZAHIR
 * @since 0.1.0
 */
@AutoConfiguration(beforeName = {
        // Boot's cache auto-configuration backs off once a CacheManager exists.
        "org.springframework.boot.cache.autoconfigure.CacheAutoConfiguration",
        // The Redis health indicator is conditional on the connection factory created here.
        "org.springframework.boot.data.redis.autoconfigure.health.DataRedisHealthContributorAutoConfiguration"
})
@EnableConfigurationProperties(ResilientCacheProperties.class)
public class ResilientCacheAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(CachingConfigurer.class)
    static CachingConfigurer fallThroughCachingConfigurer() {
        return new CachingConfigurer() {
            @Override
            public CacheErrorHandler errorHandler() {
                return new FallThroughCacheErrorHandler();
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean(CacheManager.class)
    @ConditionalOnProperty(name = "resilient-cache.provider", havingValue = "caffeine", matchIfMissing = true)
    CacheManager cacheManager(ResilientCacheProperties properties, ObjectProvider<CacheNamesProvider> cacheNames) {
        return CacheManagerFactory.caffeine(registeredNames(cacheNames), properties);
    }

    /**
     * The union of every provider's names.
     *
     * @throws IllegalStateException when no provider declares a name, or one declares a blank name
     */
    static Set<String> registeredNames(ObjectProvider<CacheNamesProvider> providers) {
        Set<String> names = providers.orderedStream()
                .flatMap(provider -> provider.cacheNames().stream())
                .collect(Collectors.toUnmodifiableSet());
        if (names.isEmpty()) {
            throw new IllegalStateException("No cache names are registered: declare a CacheNamesProvider bean "
                    + "listing the caches this application uses");
        }
        if (names.stream().anyMatch(name -> !StringUtils.hasText(name))) {
            throw new IllegalStateException("A CacheNamesProvider declares a blank cache name: " + names);
        }
        return names;
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "resilient-cache.provider", havingValue = "redis")
    static class RedisCacheConfiguration {

        @Bean
        @ConditionalOnMissingBean(RedisConnectionFactory.class)
        LettuceConnectionFactory redisConnectionFactory(ResilientCacheProperties properties) {
            ResilientCacheProperties.Redis redis = properties.redis();
            RedisStandaloneConfiguration standaloneConfiguration =
                    new RedisStandaloneConfiguration(redis.host(), redis.port());
            standaloneConfiguration.setDatabase(redis.database());
            if (StringUtils.hasText(redis.password())) {
                standaloneConfiguration.setPassword(RedisPassword.of(redis.password()));
            }
            LettuceClientConfiguration.LettuceClientConfigurationBuilder clientConfiguration =
                    LettuceClientConfiguration.builder().commandTimeout(redis.timeout());
            if (redis.ssl()) {
                // Peer verification stays on (Lettuce's default): TLS without it would accept any server.
                clientConfiguration.useSsl();
            }
            return new LettuceConnectionFactory(standaloneConfiguration, clientConfiguration.build());
        }

        @Bean
        @ConditionalOnMissingBean(CacheManager.class)
        CacheManager cacheManager(ResilientCacheProperties properties, RedisConnectionFactory connectionFactory,
                                  ObjectProvider<CacheNamesProvider> cacheNames, BeanFactory beanFactory) {
            return CacheManagerFactory.redisWithStandby(registeredNames(cacheNames), properties, connectionFactory,
                    trustedPackages(properties, beanFactory));
        }

        /**
         * The configured trusted packages or, when none are, the application's own: the packages Spring Boot
         * records for its {@code @SpringBootApplication} (or {@code @AutoConfigurationPackage}) classes.
         * Empty when neither is known; the cache manager then refuses to start.
         */
        static List<String> trustedPackages(ResilientCacheProperties properties, BeanFactory beanFactory) {
            List<String> configured = properties.redis().trustedPackages();
            if (!configured.isEmpty() || !AutoConfigurationPackages.has(beanFactory)) {
                return configured;
            }
            // A class in the default package records "", which must not become a trust-everything entry.
            return AutoConfigurationPackages.get(beanFactory).stream().filter(StringUtils::hasText).toList();
        }
    }
}
