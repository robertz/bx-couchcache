# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

----

## [Unreleased]

## [0.1.2] - 2026-11-21

### Changed

* The shared-connection lookup key is hashed, so the password is no longer held in memory as part of it
* Readme: a Security section (deserialization trust, TLS, prefix overlap, key length, lock limits)

## [0.1.1] - 2026-11-21

### Fixed

* A script that ended with a cache still open printed `Exception in thread "dnsjava NIO shutdown hook" ... Class loader [couchcache] is closed`. The module now shuts its caches down when it unloads, and stops the SDK's DNS client with the last connection

## [0.1.0] - 2026-11-21

### Added

* First iteration of this module: `CouchCache` cache provider with session storage and `bx:lock` distributed locking
* `getObjectStore()` returns a delegating object store that reports `isDistributed() == true`, so BoxExpress accepts the cache as durable for sessions
* Range scans are consistent with the provider's own writes, so `getKeys`, `getSize` and `clearAll` no longer miss a key written just before
* BoxExpress integration tests: sessions (including over HTTP), rate limiting and cluster mode
* Two-JVM BoxExpress integration test (sessions, rate limiting, cluster members, key listing and `bx:lock` across processes)
* Outage and startup-failure tests; the suite also passes on Couchbase Server 7.6.2
* Testcontainers integration tests for caching, expiry, key prefixes, serialization, sessions and locks

### Changed

* Minimum BoxLang version is 1.18.0; the module is built and tested against BoxLang 1.19.0
* Caches with the same connection string, credentials and bucket share one Couchbase connection
* Range scans use the KV timeout instead of the SDK's 75 second default
* `set()` only does its insert-or-update lookup when an interceptor listens for those events
* `./gradlew shadowJar` on its own now includes the ServiceLoader registration
* Pinned the Couchbase Java SDK to 3.13.1

### Fixed

* A cache that failed to start left its Couchbase connection open and retrying in the background
* A cache pointed at an unreachable host reported a disconnect timeout instead of the connection failure
* Lock expiry under one second threw `InvalidArgumentException`. Lock TTLs are now rounded up to whole seconds.
