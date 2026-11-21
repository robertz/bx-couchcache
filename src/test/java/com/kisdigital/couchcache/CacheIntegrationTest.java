package com.kisdigital.couchcache;

import static com.google.common.truth.Truth.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import ortus.boxlang.runtime.cache.providers.ICacheProvider;
import ortus.boxlang.runtime.events.BoxEvent;
import ortus.boxlang.runtime.scopes.Key;
import ortus.boxlang.runtime.types.Array;
import ortus.boxlang.runtime.types.IStruct;
import ortus.boxlang.runtime.types.Query;

public class CacheIntegrationTest extends BaseIntegrationTest {

	static ICacheProvider cache;

	@BeforeAll
	public static void createCaches() {
		cache = createCache( "basic" );
	}

	@DisplayName( "The module registers the CouchCache provider and the cache is a CouchCacheProvider" )
	@Test
	public void testProviderRegistered() {
		assertThat( moduleService.getRegistry().containsKey( moduleName ) ).isTrue();
		assertThat( cache.getClass().getName() ).isEqualTo( "com.kisdigital.couchcache.CouchCacheProvider" );
	}

	@DisplayName( "set / get / lookup / clear" )
	@Test
	public void testSetGetLookupClear() {
		cache.set( "crud", "hello" );
		assertThat( cache.get( "crud" ).get() ).isEqualTo( "hello" );
		assertThat( cache.lookup( "crud" ) ).isTrue();
		assertThat( rawExists( "basic:crud" ) ).isTrue();

		assertThat( cache.clear( "crud" ) ).isTrue();
		assertThat( cache.get( "crud" ).isPresent() ).isFalse();
		assertThat( cache.lookup( "crud" ) ).isFalse();
		assertThat( cache.clear( "crud" ) ).isFalse();
	}

	@DisplayName( "set announces insert for a new key and update for an existing one" )
	@Test
	public void testSetEvents() {
		ICacheProvider	observed	= createCache( "observed" );
		List<String>	events		= new java.util.concurrent.CopyOnWriteArrayList<>();
		// With nothing listening the provider skips its insert-or-update lookup; make sure that is the state the other tests run in
		assertThat( observed.getInterceptorPool().hasState( BoxEvent.AFTER_CACHE_ELEMENT_INSERT ) ).isFalse();
		assertThat( runtime.getInterceptorService().hasState( BoxEvent.AFTER_CACHE_ELEMENT_INSERT ) ).isFalse();
		assertThat( runtime.getInterceptorService().hasState( BoxEvent.AFTER_CACHE_ELEMENT_UPDATED ) ).isFalse();

		observed.getInterceptorPool().register( data -> {
			events.add( "insert:" + data.get( "key" ) );
			return false;
		}, BoxEvent.AFTER_CACHE_ELEMENT_INSERT.key() );
		observed.getInterceptorPool().register( data -> {
			events.add( "update:" + data.get( "key" ) );
			return false;
		}, BoxEvent.AFTER_CACHE_ELEMENT_UPDATED.key() );

		observed.set( "evt", 1 );
		observed.set( "evt", 2 );
		assertThat( events ).containsExactly( "insert:evt", "update:evt" ).inOrder();
	}

	@DisplayName( "getOrSet computes once, then serves the stored value" )
	@Test
	public void testGetOrSet() {
		cache.clearQuiet( "lazy" );
		AtomicInteger calls = new AtomicInteger();
		assertThat( cache.getOrSet( "lazy", () -> "v" + calls.incrementAndGet() ) ).isEqualTo( "v1" );
		assertThat( cache.getOrSet( "lazy", () -> "v" + calls.incrementAndGet() ) ).isEqualTo( "v1" );
		assertThat( calls.get() ).isEqualTo( 1 );
	}

	@DisplayName( "timeout 0 is stored with no Couchbase expiry" )
	@Test
	public void testEternal() {
		cache.set( "eternal", "forever", 0 );
		assertThat( cache.get( "eternal" ).get() ).isEqualTo( "forever" );
		assertThat( rawExpiry( "basic:eternal" ).isPresent() ).isFalse();
	}

	@DisplayName( "the default timeout is applied as the Couchbase expiry" )
	@Test
	public void testDefaultTimeout() {
		cache.set( "defaulted", "x" );
		long seconds = Duration.between( Instant.now(), rawExpiry( "basic:defaulted" ).get() ).toSeconds();
		assertThat( seconds ).isAtLeast( 3500 );
		assertThat( seconds ).isAtMost( 3600 );
	}

	@DisplayName( "a short timeout really expires" )
	@Test
	public void testExpiry() {
		cache.set( "short", "gone soon", 2 );
		assertThat( cache.get( "short" ).isPresent() ).isTrue();
		sleep( 4000 );
		assertThat( cache.get( "short" ).isPresent() ).isFalse();
		assertThat( cache.lookup( "short" ) ).isFalse();
		assertThat( cache.getKeys().contains( "short" ) ).isFalse();
	}

	@DisplayName( "resetTimeoutOnAccess extends the TTL; without it the same sequence expires" )
	@Test
	public void testResetTimeoutOnAccess() {
		ICacheProvider sliding = createCache( "sliding", "resetTimeoutOnAccess", true );

		sliding.set( "s", "v", 4 );
		cache.set( "fixed", "v", 4 );
		Instant originalExpiry = rawExpiry( "sliding:s" ).get();

		sleep( 2500 );
		assertThat( sliding.get( "s" ).isPresent() ).isTrue();
		assertThat( cache.get( "fixed" ).isPresent() ).isTrue();

		sleep( 2700 );
		assertThat( rawExpiry( "sliding:s" ).get().isAfter( originalExpiry ) ).isTrue();
		assertThat( sliding.getQuiet( "s" ).isPresent() ).isTrue();
		assertThat( cache.getQuiet( "fixed" ).isPresent() ).isFalse();
	}

	@DisplayName( "a timeout over 30 days is stored as the right absolute expiry" )
	@Test
	public void testLongTimeout() {
		Duration forty = Duration.ofDays( 40 );
		cache.set( "long", "v", forty );
		cache.set( "longSeconds", "v", forty.toSeconds() );

		for ( String id : List.of( "basic:long", "basic:longseconds" ) ) {
			long drift = Math.abs( Duration.between( Instant.now().plus( forty ), rawExpiry( id ).get() ).toSeconds() );
			assertThat( drift ).isLessThan( 60 );
		}
		assertThat( cache.get( "long" ).isPresent() ).isTrue();
	}

	@DisplayName( "mixed-case keys resolve to one document" )
	@Test
	public void testMixedCaseKeys() {
		cache.set( "MixedCase", "one" );
		cache.set( "MIXEDCASE", "two" );
		assertThat( cache.get( "mixedcase" ).get() ).isEqualTo( "two" );
		assertThat( cache.get( "mIxEdCaSe" ).get() ).isEqualTo( "two" );
		assertThat( rawExists( "basic:mixedcase" ) ).isTrue();
		assertThat( rawExists( "basic:MixedCase" ) ).isFalse();
		assertThat( cache.getKeys().stream().filter( k -> k.toString().equalsIgnoreCase( "mixedcase" ) ).count() ).isEqualTo( 1 );
		assertThat( cache.getKeys().contains( "mixedcase" ) ).isTrue();
	}

	@DisplayName( "getKeys / getSize / clearAll see this provider's own writes immediately" )
	@Test
	public void testScanSeesOwnWrites() {
		ICacheProvider fresh = createCache( "fresh" );
		for ( int i = 1; i <= 100; i++ ) {
			fresh.set( "k" + i, i );
			assertThat( fresh.getSize() ).isEqualTo( i );
		}
		for ( int i = 1; i <= 50; i++ ) {
			fresh.set( "again" + i, i );
			fresh.clearAll();
			assertThat( fresh.lookupQuiet( "again" + i ) ).isFalse();
		}
		assertThat( fresh.getSize() ).isEqualTo( 0 );
	}

	@DisplayName( "getKeys / getSize / clearAll stay inside the keyPrefix when two caches share a collection" )
	@Test
	public void testPrefixIsolation() {
		ICacheProvider	alpha	= createCache( "alpha" );
		ICacheProvider	beta	= createCache( "beta", "keyPrefix", "custom-beta:" );
		alpha.clearAll();
		beta.clearAll();

		alpha.set( "a1", 1 );
		alpha.set( "a2", 2 );
		alpha.set( "shared", "alpha" );
		beta.set( "b1", 1 );
		beta.set( "shared", "beta" );

		assertThat( rawExists( "alpha:shared" ) ).isTrue();
		assertThat( rawExists( "custom-beta:shared" ) ).isTrue();
		assertThat( alpha.getSize() ).isEqualTo( 3 );
		assertThat( beta.getSize() ).isEqualTo( 2 );
		assertThat( alpha.getKeys() ).containsExactly( "a1", "a2", "shared" );
		assertThat( beta.getKeys() ).containsExactly( "b1", "shared" );
		assertThat( alpha.get( "shared" ).get() ).isEqualTo( "alpha" );
		assertThat( beta.get( "shared" ).get() ).isEqualTo( "beta" );

		alpha.clearAll();
		assertThat( alpha.getSize() ).isEqualTo( 0 );
		assertThat( beta.getSize() ).isEqualTo( 2 );
		assertThat( beta.get( "shared" ).get() ).isEqualTo( "beta" );
	}

	@DisplayName( "a struct, array, query and class instance round-trip through serialization" )
	@Test
	public void testSerializationRoundTrip() {
		// @formatter:off
		runtime.executeSource(
		    """
			myStruct = { "name" : "rob", "nested" : { "n" : 42, "flag" : true } };
			myArray  = [ 1, "two", { "three" : 3 }, [ 4 ] ];
			myQuery  = queryNew( "id,name", "integer,varchar", [ [ 1, "one" ], [ 2, "two" ] ] );
			myObject = new src.test.resources.models.Widget( "blue" );
			""",
		    context
		);
		// @formatter:on
		for ( String name : List.of( "myStruct", "myArray", "myQuery", "myObject" ) ) {
			cache.set( "ser-" + name, variables.get( Key.of( name ) ) );
		}

		IStruct struct = ( IStruct ) cache.get( "ser-myStruct" ).get();
		assertThat( struct ).isNotSameInstanceAs( variables.get( Key.of( "myStruct" ) ) );
		assertThat( struct.get( Key.of( "name" ) ) ).isEqualTo( "rob" );
		assertThat( ( ( IStruct ) struct.get( Key.of( "nested" ) ) ).get( Key.of( "n" ) ) ).isEqualTo( 42 );
		assertThat( ( ( IStruct ) struct.get( Key.of( "nested" ) ) ).get( Key.of( "flag" ) ) ).isEqualTo( true );

		Array array = ( Array ) cache.get( "ser-myArray" ).get();
		assertThat( array.size() ).isEqualTo( 4 );
		assertThat( array.get( 1 ) ).isEqualTo( "two" );
		assertThat( ( ( IStruct ) array.get( 2 ) ).get( Key.of( "three" ) ) ).isEqualTo( 3 );

		Query query = ( Query ) cache.get( "ser-myQuery" ).get();
		assertThat( query.size() ).isEqualTo( 2 );
		assertThat( query.getRowAsStruct( 1 ).get( Key.of( "name" ) ) ).isEqualTo( "two" );

		variables.put( Key.of( "restored" ), cache.get( "ser-myObject" ).get() );
		assertThat( variables.get( Key.of( "restored" ) ) ).isNotSameInstanceAs( variables.get( Key.of( "myObject" ) ) );
		runtime.executeSource( "result = restored.greet() & '|' & restored.getLabel()", context );
		assertThat( variables.get( result ) ).isEqualTo( "widget:blue|blue" );
	}
}
