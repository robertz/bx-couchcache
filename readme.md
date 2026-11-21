# bx-couchcache

A Couchbase cache provider for BoxLang. It covers caching, session storage and `bx:lock` distributed locking. It doesn't do vector memory: if you want that, use Ortus's `bx-couchbase`, which needs a BoxLang+ subscription.

## Configure

`boxlang.json`:

```json
"caches": {
  "couch": {
    "provider": "CouchCache",
    "properties": {
      "connectionString": "couchbase://localhost",
      "username": "${COUCHBASE_USER}",
      "password": "${COUCHBASE_PASSWORD}",
      "bucket": "cache",
      "scope": "_default",
      "collection": "_default",
      "keyPrefix": "couch:",
      "defaultTimeout": 3600,
      "resetTimeoutOnAccess": false
    }
  },
  "couchSessions": {
    "provider": "CouchCache",
    "properties": {
      "connectionString": "couchbase://localhost",
      "username": "${COUCHBASE_USER}",
      "password": "${COUCHBASE_PASSWORD}",
      "bucket": "cache",
      "collection": "sessions",
      "resetTimeoutOnAccess": true
    }
  }
}
```

Sessions: in `Application.bx`, set `this.sessionStorage = "couchSessions"`. The cache must already exist when the application starts. Session changes are written back at the end of each request by the BoxLang web runtime; a plain scripting context (CLI) only stores the session when it is created.

Locks: `bx:lock name="checkout" cacheName="couch" timeout=5 { ... }`. Locks are exclusive only: the runtime rejects `readonly` locks when a `cacheName` is given. They are not reentrant. The runtime sets the lock's TTL to `timeout + 5` seconds and never renews it, so a body that runs longer than that loses the lock. `timeout=0` makes a single attempt instead of waiting forever. Couchbase expiry works in whole seconds, so lock TTLs are rounded up to the next second.

## BoxExpress

[BoxExpress](https://github.com/robertz/boxlang-express) has no `Application.bx` session handling of its own; sessions go through its session middleware and a named cache. Point it at a `CouchCache` cache from `boxlang.json`:

```js
app.use( boxExpressSession( {
	cache: "couchSessions",
	requireDurable: true,
	resave: false,
	saveUninitialized: false
} ) )
```

BoxExpress decides whether a cache is shared by calling `cache( name ).getObjectStore().isDistributed()`. The provider returns an object store that answers `true`, so `requireDurable: true` passes and there is no "in-memory store" warning. BoxExpress sets the document's expiry to the session's `maxAge` on every write, so leave `resetTimeoutOnAccess` off for this cache.

The same cache works for the other BoxExpress features that take a cache:

```js
app.use( boxExpressRateLimit( { max: 100, windowMs: 60000, cache: "couch", requireDurable: true } ) )
app.set( "cluster", { enabled: true, name: "ws://10.0.1.4:3000", cacheProvider: "couch" } )
```

Rate-limit counters are a read followed by a write, so two instances counting the same client at the same instant can lose a hit. That is how BoxExpress's cache store works on any cache. Cluster peer discovery lists keys with a range scan: a node sees its own heartbeat at once and another node's within a few milliseconds.

## Properties

| Property | Required | Default |
|---|---|---|
| `connectionString`, `username`, `password`, `bucket` | yes | — |
| `scope` / `collection` | no | `_default` |
| `keyPrefix` | no | `<cacheName>:` |
| `connectTimeout` (s) | no | 10 (how long to wait for the bucket to be ready) |
| `defaultTimeout` (s) | no | 3600 (0 = never expire) |
| `resetTimeoutOnAccess` | no | false |

## When Couchbase is unreachable

- Reads, writes, lookups, clears and lock attempts throw after the SDK's KV timeout (2.5 seconds by default). Nothing is retried or served from memory by the provider.
- A write or clear that timed out was still sent. Couchbase can apply it once it is reachable again, so treat a timed-out write as "maybe".
- The cache recovers by itself when Couchbase comes back; no restart is needed.
- `getKeys`, `getSize` and `clearAll` use the same KV timeout as everything else, not the SDK's 75 second range-scan default.
- A cache that can't reach its bucket at startup throws after `connectTimeout`, isn't registered, and closes its connection.
- BoxExpress sessions with the default `fallback: true` keep working from per-process memory during the outage and go back to Couchbase afterwards. With `fallback: false` a request that reads a session answers 500. A request that only creates a session still answers 200, because BoxExpress logs a failed save and retries it after the response, so that session is lost.

## Connections

Caches with the same connection string, credentials and bucket share one Couchbase connection, which closes when the last of them shuts down. Caches on different buckets get a connection each: a connection asked to open a bucket that doesn't exist stops serving its other buckets, so one misconfigured cache would otherwise break the rest.

## Differences from BoxCacheProvider

- **Couchbase handles expiry.** There's no reaper task, no eviction, and `maxObjects` is ignored.
- **`lastAccessTimeout` isn't enforced.** It's stored on the entry but nothing acts on it. Use `resetTimeoutOnAccess` for sliding expiry.
- **Per-entry `hits` and `lastAccessed` aren't saved.** Saving them would turn every read into a write. Cache-level stats still work, but they're per node.
- **Keys are lowercased.** BoxLang keys are case-insensitive and Couchbase IDs aren't. `getKeys()` returns the lowercase form.
- **`getKeys`, `getSize`, `clearAll` and the metadata reports scan every document under the prefix**, which costs O(n). They need Couchbase Server 7.6+ (KV range scan), but no N1QL index. Scans are made consistent with this provider's own writes, so a node always sees what it just wrote. A write from another node can be a few milliseconds behind (up to 80ms measured before this was added).
- **Values are serialized Java objects, not JSON.** Only BoxLang can read them.
- **Don't give two caches overlapping prefixes** (`app:` and `app:cart:`). The shorter one's `getKeys` and `clearAll` would include the longer one's entries.
- **`getOrSet` only locks within one node.** Two nodes can both compute a cold key. If that matters, wrap the call in `acquireLock`.

## Build

```
./gradlew downloadBoxLang
./gradlew build
```

`downloadBoxLang` puts the BoxLang jar in `src/test/resources/libs/`. On the `development` branch (the default when `BRANCH` isn't set) the build uses `boxlang-1.20.0-snapshot.jar`. A sibling `../boxlang` build is used instead if it exists. Run the two commands separately: Gradle refuses to download and compile in one invocation.

The build pins Couchbase Java SDK 3.13.1. The module jar bundles the SDK; its Netty and Jackson are relocated under `com.couchbase.client.core.deps`.

## Tests

Two BoxLang runtimes started at the same instant on one machine intermittently failed to load module jars in testing ("No cache provider with the name [CouchCache] is registered", or BoxExpress's "Cannot load interface class"). Starting them one after the other avoided it. This is the runtime's module loading, not this module, but stagger instance start-up on a shared host.

`./gradlew test` needs Docker. The integration tests start Couchbase Server with Testcontainers, load the built module from `build/module`, and create caches through `CacheService.createCache()` with the `CouchCache` provider. The image defaults to `couchbase/server:community-8.0.2`; set `COUCHBASE_IMAGE` to use another.

Verified by the suite against Couchbase Server Community 8.0.2 and 7.6.2:

- set, get, lookup, clear, getOrSet, timeout 0 (stored with no expiry), default timeout
- a 2 second timeout expires, and expired keys drop out of `getKeys()`
- `resetTimeoutOnAccess` extends the TTL
- a 40 day timeout is stored as the right absolute expiry
- mixed-case keys resolve to one document
- `getKeys`, `getSize` and `clearAll` stay inside the `keyPrefix` when caches share a collection
- a struct, array, query and class instance round-trip
- session data written in one request is read back in a new request context (using the same end-of-request persist call the web runtime makes)
- locks: acquire and release, blocking then timing out, hand-off on release, TTL expiry, renew, a stale holder can't release or renew the new owner's lock, and `bx:lock cacheName` from script

- `getKeys`, `getSize` and `clearAll` see this provider's own writes immediately
- `set` announces insert and update events when something listens for them
- BoxExpress sessions: written through one app instance and read back by a second, both with `app.inject()` and over real HTTP between two listening servers; `req.destroySession()` removes the session; the document's expiry matches `maxAge`; `requireDurable: true` is accepted
- BoxExpress rate limiting: the count is shared by two app instances
- BoxExpress cluster mode: two peers see each other at once, agree on one manager, and the survivor takes over when the manager stops

- outages: with the Couchbase container frozen, every operation fails in under 6 seconds and the cache works again once it is unfrozen; BoxExpress sessions fall back and recover
- a cache that can't reach its bucket fails within `connectTimeout`, is not registered, and leaves no SDK threads behind

- several caches share one connection, and shutting one down leaves the others working
- two BoxExpress servers in separate JVMs, each loading the built module from `boxlang_modules`: a session written on one is read on the other, the rate limit is counted across both, each lists the other as a cluster member, a key written on one appears in the other's `getKeys()` (0 of 100 lagged), and `bx:lock cacheName` on one excludes the other

The BoxExpress tests need a checkout at `../boxlang-express` (or `BOXEXPRESS_PATH`) and are skipped without one. They were run against BoxExpress 0.2.29.

Not verified: BoxExpress's clustered scheduler and STOMP relay on this provider, a reverse proxy in front of the instances, Couchbase Enterprise Edition, a node failover (the outage tests freeze a single node), multi-node clusters, a real web server request cycle, and behaviour under load.
