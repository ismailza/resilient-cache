# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

`resilient-cache-spring-boot-starter`: a general-purpose Spring Boot starter that supplies a Caffeine or
Redis `CacheManager` with a fixed set of cache names, per-cache TTLs, an in-memory standby while Redis is
unreachable, and cache errors that never fail a request. The README is the consumer's guide; this file is
for changing the starter.

## Build & Run Commands

```bash
./mvnw verify                      # build and test
./mvnw test -Dtest=CacheManagerFactoryTests
./mvnw install                     # installs ${revision} (0.1.0-SNAPSHOT) into ~/.m2
```

The version lives only in the `revision` property, which stays a snapshot. The Release workflow passes the
version it cuts (`-Drevision=0.2.0`). Never tag by hand.

## Layout

```
pom.xml
src/main/java/io/github/ismailza/resilientcache/
src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
src/main/resources/META-INF/spring.factories   # registers SpringDataRedisAutoConfigurationFilter
```

## Rules

- **Generic.** No application or domain concepts. What differs per application comes in through
  properties or a bean the application declares (`CacheNamesProvider`).
- **Auto-configuration, overridable.** Guard every bean with `@ConditionalOnMissingBean`. Order against
  Boot's auto-configurations with `beforeName`/`afterName` strings. Never reuse a Boot class's simple name
  (`CacheAutoConfiguration`, `CacheProperties`).
- **Properties are a contract.** They live under `resilient-cache.*` as the `ResilientCacheProperties`
  record. Renaming or removing one is a `feat!` with a `BREAKING CHANGE:` footer (a minor bump below 1.0).
- **Fail at startup, not at first use**, with a message naming the property.
- **Keys and values stay out of logs.** The starter does not know what they hold; log the cache name and
  operation only, and describe failures through `CacheFailures.describe`.
- **Versions come from the consumer.** Dependencies are declared without versions, managed by the Boot BOM
  of `spring-boot.version`. Java 21 bytecode (`maven.compiler.release`).

## Cache behaviour to preserve

- Only names declared by `CacheNamesProvider` beans exist. Keep that fail-fast contract.
- `SpringDataRedisAutoConfigurationFilter` drops Boot's Redis auto-configurations. When Boot renames or adds
  one, update `REPLACED`; the filter test fails otherwise.
- Redis keys are `<key-prefix>:<cache>:<key>`, lower case; the prefix is required with Redis.
- The standby's TTLs never exceed `fallback-time-to-live`, `time-to-live`, nor a shorter per-cache override.
- Only an unreachable Redis (`CacheFailures.isUnavailability`) marks the primary down for every cache
  (`PrimaryAvailability`); one entry that fails to read must not.
- Redis entries are read back only as trusted classes (`redis.trusted-packages`, defaulting to the
  application's auto-configuration packages) or the JDK value types of
  `CacheManagerFactory.valueTypeValidator`, matched by exact package. Never go back to
  `enableUnsafeDefaultTyping`, and never widen the list to a whole JDK package prefix. `RedisValueTypingTests`
  plants entries that must be refused.
- Changing the value serializer makes entries written by the previous version unreadable: say so in the
  commit, as applications then read through a cold cache after upgrading.

## Testing

- Tests live in the package of their subject. Plain classes get plain JUnit/Mockito tests;
  auto-configuration uses `ApplicationContextRunner`; anything that depends on import filters needs a real
  `@EnableAutoConfiguration` boot (`SpringDataRedisAutoConfigurationFilterTests`).
- No test needs Docker or a running Redis: Lettuce connects lazily, and Redis managers are built on a
  mocked connection factory.

## Commits

Conventional Commits (`feat`, `fix`, `docs`, `refactor`, `test`, `build`, `ci`, `chore` …); release notes are
built from `feat`, `fix` and breaking commits (`.github/scripts/release-notes.mjs`). Imperative, lower-case
subject, no trailing period.
