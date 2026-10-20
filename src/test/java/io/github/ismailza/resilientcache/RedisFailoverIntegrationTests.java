/*
 * Copyright 2026 Ismail ZAHIR
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.ismailza.resilientcache;

import com.example.untrusted.Outsider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The starter against a real Redis: a {@code @Cacheable} service keeps answering while Redis stops
 * responding, and its writes reach Redis again once it is back. Skipped where Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
class RedisFailoverIntegrationTests {

    private static final Duration COMMAND_TIMEOUT = Duration.ofMillis(500);

    @Container
    private static final GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ResilientCacheAutoConfiguration.class))
            .withUserConfiguration(GreetingConfiguration.class)
            .withPropertyValues(
                    "resilient-cache.provider=redis",
                    "resilient-cache.key-prefix=it:failover",
                    "resilient-cache.redis.host=" + redis.getHost(),
                    "resilient-cache.redis.port=" + redis.getMappedPort(6379),
                    "resilient-cache.redis.timeout=" + COMMAND_TIMEOUT.toMillis() + "ms",
                    "resilient-cache.redis.trusted-packages=io.github.ismailza.resilientcache");

    @BeforeEach
    void emptyRedis() throws Exception {
        redisCli("FLUSHALL");
        Outsider.instantiated = false;
    }

    @Test
    @DisplayName("Should serve from the standby while Redis does not respond, and write to Redis once it is back")
    void failsOverAndBack() {
        this.runner.run(context -> {
            Greetings greetings = context.getBean(Greetings.class);

            assertThat(greetings.greet("ada")).isEqualTo("hello ada");
            assertThat(storedInRedis("ada")).isTrue();

            pauseRedis();
            try {
                // The first operation waits for the command timeout, then fails over. "ada" was mirrored
                // into the standby when it was written, so the method does not run again.
                assertThat(greetings.greet("ada")).isEqualTo("hello ada");
                assertThat(greetings.calls()).isEqualTo(1);

                // Redis is now marked down: operations go straight to the standby, without a timeout.
                long started = System.nanoTime();
                assertThat(greetings.greet("grace")).isEqualTo("hello grace");
                assertThat(greetings.greet("grace")).isEqualTo("hello grace");
                assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(COMMAND_TIMEOUT);
                assertThat(greetings.calls()).isEqualTo(2);
            }
            finally {
                unpauseRedis();
            }

            // Once the retry interval has passed, the next operation finds Redis again.
            await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
                greetings.greet("linus");
                assertThat(storedInRedis("linus")).isTrue();
            });
            // A write made during the outage stays in the standby: it never reaches Redis.
            assertThat(storedInRedis("grace")).isFalse();
        });
    }

    @Test
    @DisplayName("Should refuse an untrusted class planted in Redis without marking Redis down")
    void refusesAPlantedEntry() {
        this.runner.run(context -> {
            Greetings greetings = context.getBean(Greetings.class);
            redisCli("SET", "it:failover:greetings:mallory",
                    "{\"@class\":\"com.example.untrusted.Outsider\",\"name\":\"planted\"}");

            assertThat(greetings.greet("mallory")).isEqualTo("hello mallory");
            assertThat(Outsider.instantiated).as("an untrusted class must never be constructed").isFalse();

            // One unreadable entry leaves Redis in use for every other one.
            greetings.greet("ada");
            assertThat(storedInRedis("ada")).isTrue();
        });
    }

    private static boolean storedInRedis(String name) throws Exception {
        return "1".equals(redisCli("EXISTS", "it:failover:greetings:" + name));
    }

    private static String redisCli(String... command) throws Exception {
        String[] line = new String[command.length + 1];
        line[0] = "redis-cli";
        System.arraycopy(command, 0, line, 1, command.length);
        return redis.execInContainer(line).getStdout().trim();
    }

    // A paused container keeps its connections open but never answers: commands time out.
    private static void pauseRedis() {
        DockerClientFactory.instance().client().pauseContainerCmd(redis.getContainerId()).exec();
    }

    private static void unpauseRedis() {
        DockerClientFactory.instance().client().unpauseContainerCmd(redis.getContainerId()).exec();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableCaching
    static class GreetingConfiguration {

        @Bean
        CacheNamesProvider greetingCaches() {
            return () -> List.of("greetings");
        }

        @Bean
        Greetings greetings() {
            return new Greetings();
        }
    }

    static class Greetings {

        private final AtomicInteger calls = new AtomicInteger();

        @Cacheable("greetings")
        public String greet(String name) {
            this.calls.incrementAndGet();
            return "hello " + name;
        }

        int calls() {
            return this.calls.get();
        }
    }
}
