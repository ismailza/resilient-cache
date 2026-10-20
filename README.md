# Resilient Cache Spring Boot Starter

[![Maven Central](https://img.shields.io/maven-central/v/io.github.ismailza/resilient-cache-spring-boot-starter)](https://central.sonatype.com/artifact/io.github.ismailza/resilient-cache-spring-boot-starter)
[![CI](https://github.com/ismailza/resilient-cache/actions/workflows/ci.yml/badge.svg)](https://github.com/ismailza/resilient-cache/actions/workflows/ci.yml)
![Java 21+](https://img.shields.io/badge/Java-21%2B-blue)
![Spring Boot 4.1](https://img.shields.io/badge/Spring%20Boot-4.1-6db33f)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE)

**Redis goes down. Your application doesn't.**

One dependency gives your application a `CacheManager` that switches between Caffeine and Redis with a
single property, and is built so the cache never takes the application down:

- **A fixed set of cache names.** Only names the application declares exist; a typo in `@Cacheable`
  fails instead of quietly creating an unbounded cache.
- **Per-cache TTLs**, validated at startup.
- **An in-memory standby while Redis is unreachable.** Requests keep being served from Caffeine, and
  Redis is retried every 5 seconds instead of on every operation.
- **Cache errors never fail a request.** The method runs uncached, and the log line names the cache and
  operation, never the key or value.
- **Safe Redis deserialization.** Entries are read back only as classes of trusted packages and JDK value
  types, never as a class an attacker names.
- **No Redis connection unless you ask for one.** Spring Boot's Redis auto-configuration is filtered out,
  so a Caffeine deployment opens no connection and gets no Redis health check.

Built against Spring Boot **4.1** (Jackson 3), Java 21+.

### What an outage looks like

Redis stops answering in the middle of traffic. Your `@Cacheable` methods keep returning, and the log
reads:

```text
WARN  PrimaryAvailability : Cache primary store is unreachable on get of 'products'; serving every cache
      from the in-memory standby, retrying in PT5S: org.springframework.dao.QueryTimeoutException: Redis
      command timed out
INFO  PrimaryAvailability : Cache primary store is reachable again; leaving the in-memory standby
```

One call pays the command timeout. Every other call goes straight to the in-memory standby, and one
call every 5 seconds checks whether Redis is back. No request fails, and no code changes.

## Why

With Spring Boot's own Redis cache, the cache is a dependency the application cannot run without:

- **Redis down means requests down.** The default `CacheErrorHandler` rethrows, so every `@Cacheable`
  method fails while Redis is unreachable, after waiting for the command timeout on each call.
- **A slow outage is still an outage.** A custom error handler that swallows errors keeps requests alive,
  but each one still pays the timeout on every cache it touches, and falls back to the database at full
  load.
- **The usual JSON setup is unsafe.** Many Redis cache configurations enable default typing for any
  class, which lets whoever can write to Redis choose what the application instantiates.
- **Cache names are open-ended.** A misspelt name in `@Cacheable` silently creates a new, unbounded cache.

Fixing this by hand means an error handler, a failover cache, an outage detector, a type allowlist and
a key scheme, in every application. This starter is that code, tested against a Redis that is
paused mid-test, behind one dependency and a few properties. It stays inside Spring's cache
abstraction, so `@Cacheable` code does not change.

It is not a distributed or multi-level cache framework. If you need near caches kept in sync across
instances, cache-aside APIs beyond Spring's, or Redis Cluster and Sentinel topologies, look at
Redisson or JetCache instead.

## Installation

```xml
<dependency>
  <groupId>io.github.ismailza</groupId>
  <artifactId>resilient-cache-spring-boot-starter</artifactId>
  <version>0.1.0</version>
</dependency>
```

Releases are published to Maven Central, so no repository needs to be declared. They are also on
GitHub Packages (`https://maven.pkg.github.com/ismailza/resilient-cache`, with a token that has
`read:packages`).

## Usage

Enable caching and declare the caches the application uses:

```java
@SpringBootApplication
@EnableCaching
public class Application { … }

@Configuration
class CacheConfiguration {
    @Bean
    CacheNamesProvider cacheNames() {
        return () -> List.of("products", "categories");   // any other name fails at first use
    }
}
```

Several `CacheNamesProvider` beans may contribute; the manager holds the union of their names. An
application that declares none fails at startup.

## Configuration

```yaml
resilient-cache:
  provider: ${CACHE_PROVIDER:caffeine}          # caffeine | redis
  key-prefix: shop:${ENVIRONMENT:local}         # required with redis
  time-to-live: 1h
  fallback-time-to-live: 5m                     # TTL of the in-memory standby
  maximum-size: 10000                           # per cache, Caffeine only
  time-to-live-overrides:
    permissions: 5m
  redis:
    host: ${REDIS_HOST:localhost}
    port: ${REDIS_PORT:6379}
    password: ${REDIS_PASSWORD:}
    database: 0
    timeout: 2s
    ssl: false                                  # TLS, with peer verification
    trusted-packages: com.example.shop          # default: the application's package
    replace-boot-auto-configuration: true
```

| Property | Default | Meaning |
|---|---|---|
| `provider` | `caffeine` | `caffeine` for an in-memory cache per instance, `redis` for one shared by every instance |
| `key-prefix` | — | Namespace of the Redis keys; required with `redis` |
| `time-to-live` | `1h` | Expiry of every entry |
| `fallback-time-to-live` | `5m` | Expiry of the in-memory standby used while Redis is down |
| `maximum-size` | `10000` | Entries per cache; enforced by Caffeine only |
| `time-to-live-overrides.<cache>` | — | Per-cache expiry; every key must be a declared cache |
| `redis.host` / `port` / `database` | `localhost` / `6379` / `0` | Redis server |
| `redis.password` | — | Blank means no authentication |
| `redis.timeout` | `2s` | Command timeout |
| `redis.ssl` | `false` | Connect over TLS |
| `redis.trusted-packages` | the application's package | Packages whose classes a cached value may hold |
| `redis.replace-boot-auto-configuration` | `true` | Leave Spring Boot's Redis auto-configuration out |

### How it behaves

- **Valid settings or no startup.** Every TTL, `maximum-size` and `redis.timeout` must be positive: a
  zero TTL would mean "never expire" to Redis but "expire at once" to Caffeine.
- **Key prefix.** Redis keys read `<key-prefix>:<cache>:<key>`, in lower case. Name the application and
  every dimension whose data must never mix (environment, tenant, region), so neither another application
  nor another deployment sharing the instance reads them.
- **Failover.** With Redis, every cache is backed by an in-memory standby that serves while Redis is
  unreachable, for `fallback-time-to-live`. That is never longer than `time-to-live` or a shorter
  per-cache override, so an entry never outlives its TTL because Redis is down. Once Redis is found
  unreachable, every cache leaves it alone for 5 seconds, then one operation tries it again, so an outage
  doesn't cost each operation a command timeout. Writes and evictions made during an outage don't reach
  Redis: entries may stay stale until their TTL. Only an unreachable Redis switches every cache to the
  standby; one entry that fails to read does not.
- **Errors never fail a request.** A failed cache operation is logged and the method runs uncached. Only
  a connection or timeout failure keeps its message; any other is logged by exception type alone, since
  Jackson's and Spring's messages may quote the cached value. An application that declares its own
  `CachingConfigurer` replaces this behaviour: return a `FallThroughCacheErrorHandler` from its
  `errorHandler()` to keep it.
- **Only trusted classes are read back.** A Redis entry names the class it is rebuilt as, so whoever can
  write to Redis would otherwise choose what the application instantiates (a Jackson deserialization
  gadget). Only `trusted-packages` and JDK value types (strings, numbers, `java.time`, `java.util`
  collections, `UUID`, `Date`, `Locale`, `Currency`) are accepted; an entry naming anything else is
  refused and the method runs uncached. When `trusted-packages` is unset, the package of the
  `@SpringBootApplication` class is trusted. If it can't be detected, startup fails. JDK packages
  (`java`, `javax`, `jdk`, `sun`, `com.sun`) are refused as trusted packages.
- **TLS.** Set `ssl: true` whenever Redis is reached over a network you do not own: the password and
  every cached value cross it.
- **What may be cached.** Values are stored in Redis as JSON with type information, so cached classes
  need not be `Serializable`. Round-trip each type you cache through
  `CacheManagerFactory.redisValueSerializer(trustedPackages)` in a test: final JDK collections
  (`List.of()`, `Stream.toList()`) cannot be read back.
- **Redis for more than the cache.** An application that also uses Redis directly sets
  `resilient-cache.redis.replace-boot-auto-configuration: false` and configures `spring.data.redis.*`
  itself.

Every bean is `@ConditionalOnMissingBean`: declare your own `CacheManager`, `RedisConnectionFactory` or
`CachingConfigurer` to replace the starter's.

## Development

```bash
./mvnw verify                                 # build and test
./mvnw install                                # install the current snapshot into ~/.m2
```

Unit tests need neither Docker nor Redis. `RedisFailoverIntegrationTests` runs a real Redis with
Testcontainers and pauses it mid-test to prove failover and recovery; it is skipped where Docker is
unavailable. Use Conventional Commits: release notes are built from the `feat`, `fix` and breaking
commits. To release, run the **Prepare release** workflow with the version and merge the pull request it
opens: see [docs/RELEASE.md](docs/RELEASE.md).

## Origin

This starter grew out of the caching module of my platform libraries, where it was first written, and
was extracted from them as a standalone, application-agnostic library.

## License

[Apache License 2.0](LICENSE)
