package com.kisdigital.couchcache;

import java.time.Duration;

import ortus.boxlang.runtime.cache.providers.ILock;

/**
 * A Couchbase document used as a mutex. Not reentrant.
 * The TTL is the safety net: if the holder dies, the lock frees itself after expiry.
 */
public class CouchLock implements ILock {

	private final CouchCacheProvider	provider;
	private final String				lockKey;
	private final String				owner	= CouchCacheProvider.newOwnerToken();
	private final long					acquireTimeoutMillis;
	private Duration					expiry;
	private long						cas		= 0L;

	CouchLock( CouchCacheProvider provider, String lockKey, long acquireTimeoutMillis, long expiryMillis ) {
		this.provider				= provider;
		this.lockKey				= lockKey;
		this.acquireTimeoutMillis	= acquireTimeoutMillis;
		this.expiry					= wholeSeconds( Duration.ofMillis( expiryMillis ) );
	}

	/** Spins until acquired or the timeout passes. Timeout 0 = one attempt. */
	@Override
	public boolean acquire() {
		long deadline = System.currentTimeMillis() + this.acquireTimeoutMillis;
		while ( true ) {
			this.cas = this.provider.tryInsertLock( this.lockKey, this.owner, this.expiry );
			if ( this.cas != 0L ) {
				return true;
			}
			if ( System.currentTimeMillis() >= deadline ) {
				return false;
			}
			try {
				Thread.sleep( CouchCacheProvider.lockRetrySleep() );
			} catch ( InterruptedException e ) {
				Thread.currentThread().interrupt();
				return false;
			}
		}
	}

	@Override
	public boolean renew() {
		if ( !isLocked() ) {
			return false;
		}
		this.cas = this.provider.renewLockDoc( this.lockKey, this.owner, this.cas, this.expiry );
		return isLocked();
	}

	public boolean renew( Duration newExpiry ) {
		this.expiry = wholeSeconds( newExpiry );
		return renew();
	}

	/** True if we acquired it and haven't released it. Doesn't round-trip to check the TTL. */
	@Override
	public boolean isLocked() {
		return this.cas != 0L;
	}

	@Override
	public void release() {
		if ( isLocked() ) {
			this.provider.removeLockDoc( this.lockKey, this.cas );
			this.cas = 0L;
		}
	}

	@Override
	public void close() {
		release();
	}

	/** Couchbase expiry is in whole seconds and the SDK rejects anything between 0 and 1s, so round up. Zero stays zero (no expiry). */
	private static Duration wholeSeconds( Duration expiry ) {
		return Duration.ofSeconds( ( expiry.toMillis() + 999 ) / 1000 );
	}
}
