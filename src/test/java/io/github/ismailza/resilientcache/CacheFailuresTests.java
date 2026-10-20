/*
 * Copyright 2026 Ismail ZAHIR
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.ismailza.resilientcache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchRuntimeException;

class CacheFailuresTests {

    @Test
    @DisplayName("Should leave the value out of a serialization failure")
    void serializationFailureOmitsTheValue() {
        GenericJacksonJsonRedisSerializer serializer = CacheManagerFactory.redisValueSerializer(List.of("io.github.ismailza.resilientcache"));
        // A value Jackson cannot read as the UUID it claims to be: its message quotes the value.
        byte[] entry = """
                ["java.util.UUID","user-0612345678"]
                """.getBytes(StandardCharsets.UTF_8);
        RuntimeException failure = catchRuntimeException(() -> serializer.deserialize(entry));

        assertThat(failure.toString()).as("the raw exception does quote it").contains("0612345678");
        assertThat(CacheFailures.describe(failure))
                .startsWith("serialization failure")
                .doesNotContain("0612345678");
    }

    @Test
    @DisplayName("Should keep the message of a connection failure")
    void connectionFailureKeepsItsMessage() {
        assertThat(CacheFailures.describe(new RedisConnectionFailureException("Unable to connect to redis:6379")))
                .contains("Unable to connect to redis:6379");
    }


    @Test
    @DisplayName("Should leave the value out of a type mismatch, whose message quotes it")
    void typeMismatchOmitsTheValue() {
        Cache cache = Objects.requireNonNull(
                CacheManagerFactory.caffeine(Set.of("countries"), Duration.ofMinutes(1), 10).getCache("countries"));
        cache.put("key", "user-0612345678");
        RuntimeException failure = catchRuntimeException(() -> cache.get("key", Integer.class));

        assertThat(failure.getMessage()).as("Spring's message does quote it").contains("0612345678");
        assertThat(CacheFailures.describe(failure))
                .isEqualTo("cache failure (java.lang.IllegalStateException)");
    }

    @Test
    @DisplayName("Should keep the message of a timeout")
    void timeoutKeepsItsMessage() {
        assertThat(CacheFailures.describe(new QueryTimeoutException("Redis command timed out")))
                .contains("Redis command timed out");
    }

    @Test
    @DisplayName("Should treat only a refused connection or a timeout as an unreachable store")
    void classifiesUnavailability() {
        assertThat(CacheFailures.isUnavailability(new RedisConnectionFailureException("refused"))).isTrue();
        assertThat(CacheFailures.isUnavailability(new QueryTimeoutException("timed out"))).isTrue();
        assertThat(CacheFailures.isUnavailability(new SerializationException("bad entry"))).isFalse();
        assertThat(CacheFailures.isUnavailability(new IllegalStateException("bad entry"))).isFalse();
    }
}
