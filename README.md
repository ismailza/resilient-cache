# Resilient Cache Spring Boot Starter

A Spring Boot starter that gives an application one `CacheManager`, switchable between Caffeine and
Redis with a single property, and built so the cache never takes the application down:

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

This starter grew out of the caching module of my platform libraries, where it was first written, and
was extracted from them as a standalone, application-agnostic library.

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
./mvnw install                                # install 0.1.0-SNAPSHOT into ~/.m2
```

No test needs Docker or a running Redis. Use Conventional Commits: release notes are built from the
`feat`, `fix` and breaking commits. To release, run the **Prepare release** workflow with the version and
merge the pull request it opens: see [docs/RELEASE.md](docs/RELEASE.md).

## License

[Apache License 2.0](LICENSE)
