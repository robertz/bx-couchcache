package com.kisdigital.couchcache;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import ortus.boxlang.runtime.cache.providers.ICacheProvider;
import ortus.boxlang.runtime.cache.providers.ILockableCacheProvider;
import ortus.boxlang.runtime.scopes.Key;

public class FailureIntegrationTest extends BaseIntegrationTest {

	static ICacheProvider cache;

	@BeforeAll
	public static void createCaches() {
		cache = createCache( "outage", "resetTimeoutOnAccess", true );
	}

	private static long timed( Runnable action ) {
		long start = System.currentTimeMillis();
		action.run();
		return System.currentTimeMillis() - start;
	}

	private static long cbThreads() {
		return Thread.getAllStackTraces().keySet().stream().filter( t -> t.getName().startsWith( "cb-" ) ).count();
	}

	@DisplayName( "while Couchbase is unreachable every operation fails within a few seconds, and the cache recovers by itself" )
	@Test
	public void testOutageAndRecovery() {
		cache.set( "survivor", "still here", 600 );
		assertThat( cache.get( "survivor" ).get() ).isEqualTo( "still here" );
		cache.set( "doomed", "x", 600 );

		duringOutage( () -> {
			assertThat( timed( () -> assertThrows( RuntimeException.class, () -> cache.get( "survivor" ) ) ) ).isLessThan( 6000 );
			assertThat( timed( () -> assertThrows( RuntimeException.class, () -> cache.set( "duringOutage", "x" ) ) ) ).isLessThan( 6000 );
			assertThat( timed( () -> assertThrows( RuntimeException.class, () -> cache.lookup( "survivor" ) ) ) ).isLessThan( 6000 );
			assertThat( timed( () -> assertThrows( RuntimeException.class, () -> cache.clear( "doomed" ) ) ) ).isLessThan( 6000 );
			assertThat( timed( () -> assertThrows( RuntimeException.class, () -> cache.getSize() ) ) ).isLessThan( 6000 );
			assertThat( timed( () -> assertThrows( RuntimeException.class, () -> cache.getKeys() ) ) ).isLessThan( 6000 );
			assertThat( timed( () -> assertThrows( RuntimeException.class,
			    () -> ( ( ILockableCacheProvider ) cache ).acquireLock( "outage-lock", 0, 5000 ) ) ) ).isLessThan( 6000 );
		} );

		assertThat( timed( () -> assertThat( cache.get( "survivor" ).get() ).isEqualTo( "still here" ) ) ).isLessThan( 6000 );
		// A write that timed out was still sent, so Couchbase may apply it once it is reachable again
		System.out.println( "OUTAGE doomedStillThere=" + cache.lookupQuiet( "doomed" ) + " duringOutageWritten=" + cache.lookupQuiet( "duringOutage" ) );
		cache.set( "afterOutage", "ok" );
		assertThat( cache.get( "afterOutage" ).get() ).isEqualTo( "ok" );
		assertThat( cache.getKeys().contains( "afteroutage" ) ).isTrue();
	}

	@DisplayName( "a cache that can't reach its bucket fails to start within connectTimeout, isn't registered, and leaves no SDK threads behind" )
	@Test
	public void testStartupFailure() {
		long				threadsBefore	= cbThreads();
		RuntimeException[]	error			= new RuntimeException[ 1 ];
		long				took			= timed(
		    () -> error[ 0 ] = assertThrows( RuntimeException.class, () -> createCache( "broken", "bucket", "no-such-bucket", "connectTimeout", 3 ) ) );

		assertThat( took ).isLessThan( 8000 );
		assertThat( error[ 0 ].getMessage() ).contains( "no-such-bucket" );
		assertThat( runtime.getCacheService().hasCache( Key.of( "broken" ) ) ).isFalse();

		long deadline = System.currentTimeMillis() + 10000;
		// A leaked Cluster keeps about 25 threads; the margin absorbs other caches' pools growing by a thread or two
		while ( cbThreads() > threadsBefore + 5 && System.currentTimeMillis() < deadline ) {
			sleep( 100 );
		}
		assertThat( cbThreads() ).isAtMost( threadsBefore + 5 );
	}

	@DisplayName( "caches share one Couchbase connection, and shutting one cache down leaves the others working" )
	@Test
	public void testSharedConnection() {
		ICacheProvider	keeper			= createCache( "keeper" );
		long			threadsBefore	= cbThreads();
		for ( int i = 1; i <= 5; i++ ) {
			createCache( "extra" + i ).set( "k", i );
		}
		assertThat( cbThreads() ).isAtMost( threadsBefore + 5 );

		for ( int i = 1; i <= 5; i++ ) {
			runtime.getCacheService().shutdownCache( Key.of( "extra" + i ) );
		}
		keeper.set( "afterOthersClosed", "ok" );
		assertThat( keeper.get( "afterOthersClosed" ).get() ).isEqualTo( "ok" );
		assertThat( cache.lookup( "afterOthersClosed" ) ).isFalse();
	}

	@DisplayName( "a missing required property is reported by name" )
	@Test
	public void testMissingProperty() {
		Throwable	error		= assertThrows( RuntimeException.class,
		    () -> runtime.getCacheService().createCache( Key.of( "noProps" ), Key.of( "CouchCache" ),
		        ortus.boxlang.runtime.types.Struct.of( "connectionString", couchbase.getConnectionString() ) ) );
		String		messages	= "";
		for ( Throwable t = error; t != null; t = t.getCause() ) {
			messages += t.getMessage() + " | ";
		}
		assertThat( messages ).contains( "missing required property" );
	}
}
