# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

----

## [Unreleased]

### Added

* First iteration of this module: `CouchCache` cache provider with session storage and `bx:lock` distributed locking
* `getObjectStore()` returns a delegating object store that reports `isDistributed() == true`, so BoxExpress accepts the cache as durable for sessions
* Range scans are consistent with the provider's own writes, so `getKeys`, `getSize` and `clearAll` no longer miss a key written just before
* BoxExpress integration tests: sessions (including over HTTP), rate limiting and cluster mode
* Outage and startup-failure tests; the suite also passes on Couchbase Server 7.6.2
* Testcontainers integration tests for caching, expiry, key prefixes, serialization, sessions and locks

### Changed

* `set()` only does its insert-or-update lookup when an interceptor listens for those events
* `./gradlew shadowJar` on its own now includes the ServiceLoader registration
* Pinned the Couchbase Java SDK to 3.13.1

### Fixed

* A cache that failed to start left its Couchbase connection open and retrying in the background
* Lock expiry under one second threw `InvalidArgumentException`. Lock TTLs are now rounded up to whole seconds.
