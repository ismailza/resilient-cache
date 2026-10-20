/*
 * Copyright 2026 Ismail ZAHIR
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.ismailza.resilientcache;

import com.example.untrusted.Outsider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.support.NullValue;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A Redis entry names the class it is read back as. Whoever can write to Redis therefore chooses what
 * the application instantiates — so only trusted types may be named.
 */
class RedisValueTypingTests {

    private final GenericJacksonJsonRedisSerializer serializer = CacheManagerFactory.redisValueSerializer(List.of("io.github.ismailza.resilientcache"));

    @BeforeEach
    void reset() {
        Outsider.instantiated = false;
    }

    @Test
    @DisplayName("Should refuse to instantiate a class outside the trusted packages named by a Redis entry")
    void refusesAnUntrustedClass() {
        byte[] planted = """
                {"@class":"com.example.untrusted.Outsider","name":"planted"}
                """.getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> this.serializer.deserialize(planted)).isInstanceOf(RuntimeException.class);
        assertThat(Outsider.instantiated).as("an untrusted class must never be constructed").isFalse();
    }

    @Test
    @DisplayName("Should refuse a JDK class outside the allowed value types, such as one that opens a connection")
    void refusesAnUnlistedJdkClass() {
        byte[] planted = """
                ["java.net.URL","http://attacker.invalid/"]
                """.getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> this.serializer.deserialize(planted)).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("Should accept a class of a configured trusted package")
    void acceptsAConfiguredPackage() {
        GenericJacksonJsonRedisSerializer trusting = CacheManagerFactory.redisValueSerializer(
                List.of("com.example.untrusted"));
        Outsider outsider = new Outsider();
        outsider.setName("trusted now");

        Object read = trusting.deserialize(trusting.serialize(outsider));

        assertThat(read).isInstanceOf(Outsider.class).extracting("name").isEqualTo("trusted now");
    }

    @Test
    @DisplayName("Should round-trip the JDK value types and collections reference data is made of")
    void roundTripsJdkValues() {
        Map<String, Object> value = new HashMap<>(Map.of(
                "id", UUID.randomUUID(),
                "when", Instant.parse("2026-10-18T10:00:00Z"),
                "amount", new BigDecimal("12.50"),
                "names", new ArrayList<>(List.of("a", "b"))));

        assertThat(this.serializer.deserialize(this.serializer.serialize(value))).isEqualTo(value);
    }

    @Test
    @DisplayName("Should round-trip a cached null")
    void roundTripsNull() {
        Object read = this.serializer.deserialize(this.serializer.serialize(NullValue.INSTANCE));

        // A new instance, as before the allowlist: the cache recognizes it by type.
        assertThat(read).isInstanceOf(NullValue.class);
    }
}
