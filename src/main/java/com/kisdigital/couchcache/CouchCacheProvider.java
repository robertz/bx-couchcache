package com.kisdigital.couchcache;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;

import com.couchbase.client.core.error.CasMismatchException;
import com.couchbase.client.core.error.DocumentExistsException;
import com.couchbase.client.core.error.DocumentNotFoundException;
import com.couchbase.client.java.Bucket;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.ClusterOptions;
import com.couchbase.client.java.Collection;
import com.couchbase.client.java.codec.RawBinaryTranscoder;
import com.couchbase.client.java.kv.GetOptions;
import com.couchbase.client.java.kv.GetResult;
import com.couchbase.client.java.kv.InsertOptions;
import com.couchbase.client.java.kv.MutationResult;
import com.couchbase.client.java.kv.MutationState;
import com.couchbase.client.java.kv.RemoveOptions;
import com.couchbase.client.java.kv.ReplaceOptions;
import com.couchbase.client.java.kv.ScanOptions;
import com.couchbase.client.java.kv.ScanResult;
import com.couchbase.client.java.kv.ScanType;
import com.couchbase.client.java.kv.UpsertOptions;

import ortus.boxlang.runtime.BoxRuntime;
import ortus.boxlang.runtime.cache.BoxCache;
import ortus.boxlang.runtime.cache.BoxCacheEntry;
import ortus.boxlang.runtime.cache.ICacheEntry;
import ortus.boxlang.runtime.cache.filters.ICacheKeyFilter;
import ortus.boxlang.runtime.cache.providers.AbstractCacheProvider;
import ortus.boxlang.runtime.cache.providers.ICacheProvider;
import ortus.boxlang.runtime.cache.providers.ILock;
import ortus.boxlang.runtime.cache.providers.ILockableCacheProvider;
import ortus.boxlang.runtime.cache.store.IObjectStore;
import ortus.boxlang.runtime.config.segments.CacheConfig;
import ortus.boxlang.runtime.context.IBoxContext;
import ortus.boxlang.runtime.context.ScriptingRequestBoxContext;
import ortus.boxlang.runtime.dynamic.Attempt;
import ortus.boxlang.runtime.dynamic.casters.BooleanCaster;
import ortus.boxlang.runtime.dynamic.casters.IntegerCaster;
import ortus.boxlang.runtime.events.BoxEvent;
import ortus.boxlang.runtime.scopes.Key;
import ortus.boxlang.runtime.services.CacheService;
import ortus.boxlang.runtime.types.Array;
import ortus.boxlang.runtime.types.IStruct;
import ortus.boxlang.runtime.types.Struct;
import ortus.boxlang.runtime.types.exceptions.BoxRuntimeException;
import ortus.boxlang.runtime.types.util.BLCollector;
import ortus.boxlang.runtime.util.conversion.ObjectMarshaller;

/**
 * Couchbase-backed cache provider.
 *
 * Design choices (deliberate, see readme):
 * - Couchbase owns expiry. There is no reaper; reap() is a no-op.
 * - Entries are the serialized BoxCacheEntry (BoxLang-only format, not JSON).
 * - Per-entry hits/lastAccessed are NOT persisted (would turn every read into a write).
 * - lastAccessTimeout is NOT enforced. resetTimeoutOnAccess IS (async touch on read).
 * - Keys are lowercased: BoxLang Keys are case-insensitive, Couchbase IDs are not.
 *
 * Registered by ServiceLoader as "CouchCacheProvider", alias "CouchCache".
 */
@BoxCache( alias = "CouchCache", description = "Couchbase cache provider", distributed = true, distributedLocking = true, tags = { "couchbase",
    "distributed" } )
public class CouchCacheProvider extends AbstractCacheProvider implements ILockableCacheProvider {

	private static final Duration				LOCK_RETRY_SLEEP	= Duration.ofMillis( 50 );

	private static final Map<String, Cluster>	CLUSTERS			= new HashMap<>();
	private static final Map<Cluster, Integer>	CLUSTER_USERS		= new HashMap<>();

	private Cluster								cluster;
	private Duration							scanTimeout;
	private Collection							collection;
	private IBoxContext							context;
	private String								keyPrefix;
	private String								lockPrefix;
	private Duration							defaultTimeout;
	private Duration							defaultLastAccessTimeout;
	private boolean								resetTimeoutOnAccess;
	private IObjectStore						objectStore;

	/**
	 * Highest mutation token per vbucket for this provider's own writes. Range scans are made
	 * consistent with it, so getKeys/getSize/clearAll always see what this node just wrote.
	 * A write from another node can still be a few milliseconds behind.
	 */
	private volatile MutationState				mutations;

	/*
	 * ------------------------------------------------------------------
	 * Lifecycle
	 * ------------------------------------------------------------------
	 */

	@Override
	public synchronized ICacheProvider configure( CacheService cacheService, CacheConfig config ) {
		super.configure( cacheService, config );
		IStruct	props		= config.properties;

		// Read before connecting, so a missing property can't leave a connection behind
		String	bucketName	= required( props, "bucket" );

		this.cluster = acquireCluster( required( props, "connectionString" ), required( props, "username" ), required( props, "password" ), bucketName );
		Bucket bucket = this.cluster.bucket( bucketName );
		try {
			bucket.waitUntilReady( Duration.ofSeconds( intProp( props, "connectTimeout", 10 ) ) );
		} catch ( RuntimeException e ) {
			// Otherwise the failed cache leaves a connected Cluster retrying in the background forever
			releaseCluster( this.cluster );
			this.cluster = null;
			throw e;
		}
		// A range scan's own default is 75 seconds, which is a long time for getKeys() to hang during an outage
		this.scanTimeout				= this.cluster.environment().timeoutConfig().kvTimeout();

		this.collection					= bucket
		    .scope( stringProp( props, "scope", "_default" ) )
		    .collection( stringProp( props, "collection", "_default" ) );

		// Prefix lets several caches share one collection without clearAll() nuking each other
		this.keyPrefix					= stringProp( props, "keyPrefix", getName().getNameNoCase() + ":" );
		this.lockPrefix					= "lock:" + this.keyPrefix;

		this.defaultTimeout				= Duration.ofSeconds( intProp( props, "defaultTimeout", 3600 ) );
		this.defaultLastAccessTimeout	= Duration.ofSeconds( intProp( props, "defaultLastAccessTimeout", 1800 ) );
		this.resetTimeoutOnAccess		= BooleanCaster.cast( props.getOrDefault( Key.of( "resetTimeoutOnAccess" ), false ) );

		// Same trick JDBCStore uses: a context for ObjectMarshaller
		this.context					= new ScriptingRequestBoxContext( BoxRuntime.getInstance().getRuntimeContext() );

		this.objectStore				= new CouchObjectStore().init( this, props );

		this.reportingEnabled			= true;
		this.enabled.set( true );
		return this;
	}

	@Override
	public void shutdown() {
		this.enabled.set( false );
		if ( this.cluster != null ) {
			releaseCluster( this.cluster );
			this.cluster = null;
		}
	}

	/**
	 * Caches with the same connection string, credentials and bucket share one Cluster. It is
	 * disconnected when the last cache using it shuts down. The bucket is part of the key on purpose:
	 * a Cluster that has been asked to open a bucket that doesn't exist stops serving its other buckets.
	 */
	private static synchronized Cluster acquireCluster( String connectionString, String username, String password, String bucketName ) {
		String	key		= connectionString + "\n" + username + "\n" + password + "\n" + bucketName;
		Cluster	shared	= CLUSTERS.get( key );
		if ( shared == null ) {
			shared = Cluster.connect( connectionString, ClusterOptions.clusterOptions( username, password ) );
			CLUSTERS.put( key, shared );
		}
		CLUSTER_USERS.merge( shared, 1, Integer::sum );
		return shared;
	}

	private static synchronized void releaseCluster( Cluster shared ) {
		if ( CLUSTER_USERS.merge( shared, -1, Integer::sum ) <= 0 ) {
			CLUSTER_USERS.remove( shared );
			CLUSTERS.values().remove( shared );
			shared.disconnect();
		}
	}

	/** Couchbase expires documents itself. */
	@Override
	public void reap() {
		// no-op by design
	}

	/**
	 * The provider doesn't run on an object store. This is a delegating view of it, so callers
	 * that check getObjectStore().isDistributed() (BoxExpress does) see a shared store.
	 */
	@Override
	public IObjectStore getObjectStore() {
		return this.objectStore;
	}

	/*
	 * ------------------------------------------------------------------
	 * Reads
	 * ------------------------------------------------------------------
	 */

	@Override
	public Attempt<Object> get( String key ) {
		ICacheEntry entry = fetch( key );
		if ( entry == null ) {
			this.stats.recordMiss();
			return Attempt.empty();
		}
		this.stats.recordHit();
		if ( this.resetTimeoutOnAccess && !entry.isEternal() ) {
			// Fire and forget; a failed touch just means the original expiry stands
			this.collection.async().touch( docId( key ), Duration.ofSeconds( entry.timeout() ) );
		}
		return entry.value();
	}

	@Override
	public Attempt<Object> getQuiet( String key ) {
		ICacheEntry entry = fetch( key );
		return entry == null ? Attempt.empty() : entry.value();
	}

	@Override
	public IStruct get( String... keys ) {
		IStruct results = new Struct();
		for ( String key : keys ) {
			results.put( key, get( key ) );
		}
		return results;
	}

	@Override
	public IStruct get( ICacheKeyFilter filter ) {
		IStruct results = new Struct();
		getKeysStream( filter ).forEach( key -> results.put( key, get( key ) ) );
		return results;
	}

	@Override
	public boolean lookupQuiet( String key ) {
		return this.collection.exists( docId( key ) ).exists();
	}

	@Override
	public boolean lookup( String key ) {
		boolean found = lookupQuiet( key );
		if ( found ) {
			this.stats.recordHit();
		} else {
			this.stats.recordMiss();
		}
		return found;
	}

	@Override
	public IStruct lookup( String... keys ) {
		IStruct found = new Struct();
		for ( String key : keys ) {
			found.put( key, lookup( key ) );
		}
		return found;
	}

	@Override
	public IStruct lookup( ICacheKeyFilter filter ) {
		IStruct found = new Struct();
		getKeysStream( filter ).forEach( key -> found.put( key, lookup( key ) ) );
		return found;
	}

	/*
	 * ------------------------------------------------------------------
	 * Writes
	 * ------------------------------------------------------------------
	 */

	@Override
	public void set( String key, Object value, Object timeout, Object lastAccessTimeout, IStruct metadata ) {
		// Insert vs. update needs an extra round trip; only pay for it when something is listening
		boolean		listening	= hasListener( BoxEvent.AFTER_CACHE_ELEMENT_INSERT ) || hasListener( BoxEvent.AFTER_CACHE_ELEMENT_UPDATED );
		boolean		existed		= listening && lookupQuiet( key );
		Duration	dTime		= toDuration( timeout, this.defaultTimeout );
		Duration	dLast		= toDuration( lastAccessTimeout, this.defaultLastAccessTimeout );
		Key			boxKey		= Key.of( key );
		ICacheEntry	entry		= new BoxCacheEntry( getName(), dTime.toSeconds(), dLast.toSeconds(), boxKey, value, metadata );

		setQuiet( boxKey, entry );

		if ( !listening ) {
			return;
		}
		if ( existed ) {
			// We don't re-read the old entry just to announce it: that's another round trip per set
			announce( BoxEvent.AFTER_CACHE_ELEMENT_UPDATED, Struct.of( "cache", this, "key", boxKey, "newEntry", entry ) );
		} else {
			announce( BoxEvent.AFTER_CACHE_ELEMENT_INSERT, Struct.of( "cache", this, "key", boxKey, "entry", entry ) );
		}
	}

	@Override
	public void set( String key, Object value, Object timeout, Object lastAccessTimeout ) {
		set( key, value, timeout, lastAccessTimeout, new Struct() );
	}

	@Override
	public void set( String key, Object value, Object timeout ) {
		set( key, value, timeout, this.defaultLastAccessTimeout );
	}

	@Override
	public void set( String key, Object value ) {
		set( key, value, this.defaultTimeout, this.defaultLastAccessTimeout );
	}

	@Override
	public void set( IStruct entries ) {
		set( entries, this.defaultTimeout, this.defaultLastAccessTimeout );
	}

	@Override
	public void set( IStruct entries, Object timeout, Object lastAccessTimeout ) {
		entries.forEach( ( k, v ) -> set( k.getName(), v, timeout, lastAccessTimeout ) );
	}

	@Override
	public void setQuiet( Key key, ICacheEntry entry ) {
		// Duration.ZERO = no expiry in the Couchbase SDK, which matches BoxLang's 0 = eternal
		track( this.collection.upsert(
		    docId( key.getName() ),
		    ObjectMarshaller.serialize( this.context, entry ),
		    UpsertOptions.upsertOptions()
		        .transcoder( RawBinaryTranscoder.INSTANCE )
		        .expiry( Duration.ofSeconds( entry.timeout() ) )
		) );
	}

	/**
	 * Node-local double-checked lock, same as BoxCacheProvider. Two nodes can still
	 * both compute on a cold key. If that matters, wrap this in acquireLock().
	 */
	@Override
	public Object getOrSet( String key, Supplier<Object> provider, Object timeout, Object lastAccessTimeout, IStruct metadata ) {
		Attempt<Object> hit = get( key );
		if ( hit.isPresent() ) {
			return hit.get();
		}
		String lockKey = getName().getNameNoCase() + "-" + docId( key );
		synchronized ( lockKey.intern() ) {
			return getQuiet( key ).orElseGet( () -> {
				Object value = provider.get();
				set( key, value, timeout, lastAccessTimeout, metadata );
				return value;
			} );
		}
	}

	@Override
	public Object getOrSet( String key, Supplier<Object> provider, Object timeout, Object lastAccessTimeout ) {
		return getOrSet( key, provider, timeout, lastAccessTimeout, new Struct() );
	}

	@Override
	public Object getOrSet( String key, Supplier<Object> provider, Object timeout ) {
		return getOrSet( key, provider, timeout, this.defaultLastAccessTimeout );
	}

	@Override
	public Object getOrSet( String key, Supplier<Object> provider ) {
		return getOrSet( key, provider, this.defaultTimeout, this.defaultLastAccessTimeout );
	}

	/*
	 * ------------------------------------------------------------------
	 * Clears
	 * ------------------------------------------------------------------
	 */

	@Override
	public boolean clearQuiet( String key ) {
		try {
			track( this.collection.remove( docId( key ) ) );
			return true;
		} catch ( DocumentNotFoundException e ) {
			return false;
		}
	}

	@Override
	public boolean clear( String key ) {
		announce( BoxEvent.BEFORE_CACHE_ELEMENT_REMOVED, Struct.of( "cache", this, "key", key ) );
		boolean cleared = clearQuiet( key );
		announce( BoxEvent.AFTER_CACHE_ELEMENT_REMOVED, Struct.of( "cache", this, "key", key, "cleared", cleared ) );
		return cleared;
	}

	@Override
	public IStruct clear( String... keys ) {
		IStruct cleared = new Struct();
		for ( String key : keys ) {
			cleared.put( key, clear( key ) );
		}
		return cleared;
	}

	/** O(n) scan of this cache's prefix. Fine for admin use, not a hot path. */
	@Override
	public void clearAll() {
		getKeysStream().forEach( this::clearQuiet );
		announce( BoxEvent.AFTER_CACHE_CLEAR_ALL, Struct.of( "cache", this ) );
	}

	@Override
	public boolean clearAll( ICacheKeyFilter filter ) {
		return getKeysStream( filter ).map( this::clearQuiet ).reduce( false, Boolean::logicalOr );
	}

	/*
	 * ------------------------------------------------------------------
	 * Keys, size, reporting -- all O(n) KV range scans (Couchbase Server 7.6+)
	 * ------------------------------------------------------------------
	 */

	@Override
	public Stream<String> getKeysStream() {
		return this.collection
		    .scan( ScanType.prefixScan( this.keyPrefix ),
		        ScanOptions.scanOptions().idsOnly( true ).consistentWith( this.mutations ).timeout( this.scanTimeout ) )
		    .map( ScanResult::id )
		    .map( id -> id.substring( this.keyPrefix.length() ) );
	}

	@Override
	public Stream<String> getKeysStream( ICacheKeyFilter filter ) {
		return getKeysStream().filter( k -> filter.test( Key.of( k ) ) );
	}

	@Override
	public Array getKeys() {
		return getKeysStream().collect( BLCollector.toArray() );
	}

	@Override
	public Array getKeys( ICacheKeyFilter filter ) {
		return getKeysStream( filter ).collect( BLCollector.toArray() );
	}

	@Override
	public int getSize() {
		return ( int ) getKeysStream().count();
	}

	@Override
	public IStruct getCachedObjectMetadata( String key ) {
		ICacheEntry entry = fetch( key );
		return entry == null ? new Struct() : entry.toStruct();
	}

	@Override
	public IStruct getStoreMetadataReport( int limit ) {
		IStruct report = new Struct();
		getKeysStream().limit( limit ).forEach( k -> report.put( k, getCachedObjectMetadata( k ) ) );
		return report;
	}

	@Override
	public IStruct getStoreMetadataReport() {
		return getStoreMetadataReport( Integer.MAX_VALUE );
	}

	@Override
	public IStruct getStoreMetadataKeyMap() {
		return Struct.of(
		    "cacheName", "cacheName",
		    "hits", "hits",
		    "timeout", "timeout",
		    "lastAccessTimeout", "lastAccessTimeout",
		    "created", "created",
		    "lastAccessed", "lastAccessed",
		    "metadata", "metadata",
		    "key", "key",
		    "isEternal", "isEternal"
		);
	}

	/*
	 * ------------------------------------------------------------------
	 * Distributed locking: bx:lock cacheName="..." works once this is implemented.
	 * Lock = a document created with insert() (fails if it exists) and a TTL so a
	 * dead node can't hold it forever. CAS makes renew/release owner-only.
	 * ------------------------------------------------------------------
	 */

	@Override
	public ILock acquireLock( String lockKey, int acquireTimeoutMillis, int expiryTimeMillis ) {
		CouchLock lock = new CouchLock( this, lockKey, acquireTimeoutMillis, expiryTimeMillis );
		lock.acquire();
		return lock;
	}

	@Override
	public boolean renewLock( ILock lock, int expiryTimeMillis ) {
		return lock instanceof CouchLock couchLock
		    ? couchLock.renew( Duration.ofMillis( expiryTimeMillis ) )
		    : lock.renew();
	}

	@Override
	public void releaseLock( ILock lock ) {
		lock.release();
	}

	/** Returns the CAS on success, or 0 if the lock is held elsewhere. */
	long tryInsertLock( String lockKey, String owner, Duration expiry ) {
		try {
			return this.collection.insert( lockPrefix + lockKey, owner, InsertOptions.insertOptions().expiry( expiry ) ).cas();
		} catch ( DocumentExistsException e ) {
			return 0L;
		}
	}

	/** Returns the new CAS, or 0 if we no longer own it (expired or taken). */
	long renewLockDoc( String lockKey, String owner, long cas, Duration expiry ) {
		try {
			return this.collection.replace( lockPrefix + lockKey, owner, ReplaceOptions.replaceOptions().cas( cas ).expiry( expiry ) ).cas();
		} catch ( CasMismatchException | DocumentNotFoundException e ) {
			return 0L;
		}
	}

	void removeLockDoc( String lockKey, long cas ) {
		try {
			this.collection.remove( lockPrefix + lockKey, RemoveOptions.removeOptions().cas( cas ) );
		} catch ( CasMismatchException | DocumentNotFoundException e ) {
			// Expired or someone else holds it now. Either way, not ours to delete.
		}
	}

	static String newOwnerToken() {
		return UUID.randomUUID().toString();
	}

	static Duration lockRetrySleep() {
		return LOCK_RETRY_SLEEP;
	}

	/*
	 * ------------------------------------------------------------------
	 * Helpers
	 * ------------------------------------------------------------------
	 */

	/** Mirrors announce(): the cache's own pool, then the runtime-wide interceptors. */
	private boolean hasListener( BoxEvent event ) {
		return this.interceptorPool.hasState( event ) || BoxRuntime.getInstance().getInterceptorService().hasState( event );
	}

	/** MutationState can't be created empty, so it starts null: the scan treats null as "no consistency requirement". */
	private void track( MutationResult result ) {
		result.mutationToken().ifPresent( token -> {
			if ( this.mutations == null ) {
				synchronized ( this ) {
					if ( this.mutations == null ) {
						this.mutations = MutationState.from( token );
						return;
					}
				}
			}
			this.mutations.add( token );
		} );
	}

	private String docId( String key ) {
		return this.keyPrefix + Key.of( key ).getNameNoCase();
	}

	/**
	 * Fetch and deserialize. Deliberately does NOT call entry.isExpired(): with
	 * resetTimeoutOnAccess the doc's TTL moves but the stored 'created' doesn't,
	 * so the local check would expire live sessions. Couchbase is the authority.
	 */
	ICacheEntry fetch( String key ) {
		try {
			GetResult result = this.collection.get( docId( key ), GetOptions.getOptions().transcoder( RawBinaryTranscoder.INSTANCE ) );
			return ( ICacheEntry ) ObjectMarshaller.deserialize( this.context, result.contentAs( byte[].class ) );
		} catch ( DocumentNotFoundException e ) {
			return null;
		}
	}

	private static String required( IStruct props, String name ) {
		Object value = props.get( Key.of( name ) );
		if ( value == null || value.toString().isBlank() ) {
			throw new BoxRuntimeException( "CouchCacheProvider: missing required property [" + name + "]" );
		}
		return value.toString();
	}

	private static String stringProp( IStruct props, String name, String fallback ) {
		Object value = props.get( Key.of( name ) );
		return value == null || value.toString().isBlank() ? fallback : value.toString();
	}

	private static int intProp( IStruct props, String name, int fallback ) {
		Object value = props.get( Key.of( name ) );
		return value == null ? fallback : IntegerCaster.cast( value );
	}
}
