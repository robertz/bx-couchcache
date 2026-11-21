package com.kisdigital.couchcache;

import static com.google.common.truth.Truth.assertThat;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import ortus.boxlang.runtime.cache.providers.ILock;
import ortus.boxlang.runtime.cache.providers.ILockableCacheProvider;
import ortus.boxlang.runtime.scopes.Key;

public class LockIntegrationTest extends BaseIntegrationTest {

	static ILockableCacheProvider locks;

	@BeforeAll
	public static void createCaches() {
		locks = ( ILockableCacheProvider ) createCache( "locks" );
	}

	@DisplayName( "acquire creates the lock document, release removes it" )
	@Test
	public void testAcquireRelease() {
		ILock lock = locks.acquireLock( "basic", 1000, 10000 );
		assertThat( lock.isLocked() ).isTrue();
		assertThat( rawExists( "lock:locks:basic" ) ).isTrue();

		locks.releaseLock( lock );
		assertThat( lock.isLocked() ).isFalse();
		assertThat( rawExists( "lock:locks:basic" ) ).isFalse();
	}

	@DisplayName( "a second acquire blocks, then times out" )
	@Test
	public void testSecondAcquireTimesOut() {
		ILock	first	= locks.acquireLock( "contended", 0, 10000 );
		long	start	= System.currentTimeMillis();
		ILock	second	= locks.acquireLock( "contended", 1500, 10000 );
		long	waited	= System.currentTimeMillis() - start;

		assertThat( second.isLocked() ).isFalse();
		assertThat( waited ).isAtLeast( 1500 );
		assertThat( waited ).isLessThan( 3000 );

		locks.releaseLock( second );
		assertThat( rawExists( "lock:locks:contended" ) ).isTrue();
		locks.releaseLock( first );
	}

	@DisplayName( "a blocked acquire succeeds once the holder releases" )
	@Test
	public void testBlockedAcquireSucceedsAfterRelease() {
		ILock first = locks.acquireLock( "handoff", 0, 10000 );
		CompletableFuture.runAsync( () -> {
			sleep( 500 );
			locks.releaseLock( first );
		} );
		ILock second = locks.acquireLock( "handoff", 5000, 10000 );
		assertThat( second.isLocked() ).isTrue();
		locks.releaseLock( second );
	}

	@DisplayName( "a lock auto-expires after its TTL, and the stale holder's release does not delete the new owner's lock" )
	@Test
	public void testAutoExpiryAndStaleRelease() {
		ILock stale = locks.acquireLock( "ttl", 0, 2000 );
		assertThat( stale.isLocked() ).isTrue();
		sleep( 3500 );
		assertThat( rawExists( "lock:locks:ttl" ) ).isFalse();

		ILock owner = locks.acquireLock( "ttl", 0, 10000 );
		assertThat( owner.isLocked() ).isTrue();

		locks.releaseLock( stale );
		assertThat( rawExists( "lock:locks:ttl" ) ).isTrue();
		assertThat( locks.acquireLock( "ttl", 0, 10000 ).isLocked() ).isFalse();
		assertThat( locks.renewLock( stale, 10000 ) ).isFalse();

		locks.releaseLock( owner );
		assertThat( rawExists( "lock:locks:ttl" ) ).isFalse();
	}

	@DisplayName( "renew extends the lock's TTL" )
	@Test
	public void testRenew() {
		ILock lock = locks.acquireLock( "renew", 0, 2000 );
		assertThat( locks.renewLock( lock, 10000 ) ).isTrue();
		sleep( 3500 );
		assertThat( rawExists( "lock:locks:renew" ) ).isTrue();
		locks.releaseLock( lock );
		assertThat( rawExists( "lock:locks:renew" ) ).isFalse();
	}

	@DisplayName( "a sub-second lock expiry is accepted" )
	@Test
	public void testSubSecondExpiry() {
		ILock lock = locks.acquireLock( "subsecond", 0, 500 );
		assertThat( lock.isLocked() ).isTrue();
		locks.releaseLock( lock );
	}

	@DisplayName( "bx:lock cacheName works end to end from BoxLang script" )
	@Test
	public void testBxLock() {
		// @formatter:off
		runtime.executeSource(
		    """
			outerRan = false;
			innerRan = false;
			timedOut = false;
			heldInside = false;
			lock name="E2E" cacheName="locks" timeout=5 {
				outerRan = true;
				heldInside = cache( "locks" ).getName() == "locks";
				lock name="e2e" cacheName="locks" timeout=1 throwOnTimeout=false {
					innerRan = true;
				}
				try {
					lock name="e2e" cacheName="locks" timeout=1 {
						innerRan = true;
					}
				} catch ( any e ) {
					timedOut = e.message contains "Timeout";
				}
			}
			lock name="e2e" cacheName="locks" timeout=1 {
				result = "reacquired";
			}
			""",
		    context
		);
		// @formatter:on
		assertThat( variables.get( Key.of( "outerRan" ) ) ).isEqualTo( true );
		assertThat( variables.get( Key.of( "innerRan" ) ) ).isEqualTo( false );
		assertThat( variables.get( Key.of( "timedOut" ) ) ).isEqualTo( true );
		assertThat( variables.get( result ) ).isEqualTo( "reacquired" );
		assertThat( rawExists( "lock:locks:e2e" ) ).isFalse();
	}

	@DisplayName( "bx:lock holds the Couchbase lock document while the body runs" )
	@Test
	public void testBxLockHoldsDocument() {
		variables.put( Key.of( "probe" ), ( java.util.function.Supplier<Boolean> ) () -> rawExists( "lock:locks:probe" ) );
		runtime.executeSource(
		    """
		    lock name="probe" cacheName="locks" timeout=5 {
		    	result = probe.get();
		    }
		    """,
		    context
		);
		assertThat( variables.get( result ) ).isEqualTo( true );
		assertThat( rawExists( "lock:locks:probe" ) ).isFalse();
	}
}
