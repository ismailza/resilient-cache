/*
 * Copyright 2026 Ismail ZAHIR
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.ismailza.resilientcache;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.AutoConfigurationImportFilter;
import org.springframework.boot.autoconfigure.AutoConfigurationMetadata;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;

import java.util.Set;

/**
 * {@code SpringDataRedisAutoConfigurationFilter} leaves Spring Boot's Redis auto-configuration out of the
 * context, so the Redis connection comes from this starter alone.
 *
 * <p>Without it, Spring Boot would see Spring Data Redis on the class path and open a connection — and
 * register a Redis health check — in every deployment, including those on the Caffeine provider that run
 * no Redis at all. Each application used to exclude these classes in its own {@code application.yml}; the
 * filter makes that the starter's job, and is the only way to: a library cannot add to an application's
 * {@code spring.autoconfigure.exclude} list without replacing it.</p>
 *
 * <p>An application that uses Redis for more than its cache turns the filter off with
 * {@code resilient-cache.redis.replace-boot-auto-configuration=false} and configures
 * {@code spring.data.redis.*} itself.</p>
 *
 * @author Ismail ZAHIR
 * @since 0.1.0
 */
public class SpringDataRedisAutoConfigurationFilter implements AutoConfigurationImportFilter, EnvironmentAware {

    static final String ENABLED_PROPERTY = "resilient-cache.redis.replace-boot-auto-configuration";

    /** Every Spring Boot auto-configuration that creates Redis beans of its own. */
    static final Set<String> REPLACED = Set.of(
            "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration",
            "org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration",
            "org.springframework.boot.data.redis.autoconfigure.DataRedisRepositoriesAutoConfiguration"
    );

    private @Nullable Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public boolean[] match(@Nullable String[] autoConfigurationClasses,
                           AutoConfigurationMetadata autoConfigurationMetadata) {
        boolean enabled = this.environment == null
                || this.environment.getProperty(ENABLED_PROPERTY, Boolean.class, true);
        boolean[] matches = new boolean[autoConfigurationClasses.length];
        for (int index = 0; index < autoConfigurationClasses.length; index++) {
            // A null entry was already removed by an earlier filter.
            String candidate = autoConfigurationClasses[index];
            matches[index] = !enabled || candidate == null || !REPLACED.contains(candidate);
        }
        return matches;
    }
}
