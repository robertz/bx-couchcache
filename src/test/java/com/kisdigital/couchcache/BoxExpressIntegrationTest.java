package com.kisdigital.couchcache;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.couchbase.client.java.kv.ScanType;

import ortus.boxlang.runtime.cache.providers.ICacheProvider;
import ortus.boxlang.runtime.modules.ModuleRecord;
import ortus.boxlang.runtime.scopes.Key;
import ortus.boxlang.runtime.types.IStruct;

/**
 * Runs against a BoxExpress checkout: ../boxlang-express by default, or BOXEXPRESS_PATH.
 * Skipped when neither exists.
 */
public class BoxExpressIntegrationTest extends BaseIntegrationTest {

	static final Path		BOXEXPRESS	= Path.of( System.getenv().getOrDefault( "BOXEXPRESS_PATH", "../boxlang-express" ) ).toAbsolutePath().normalize();
	static final Key		boxexpress	= Key.of( "boxexpress" );
	static ICacheProvider	cache;

	@BeforeAll
	public static void loadBoxExpress() {
		assumeTrue( Files.exists( BOXEXPRESS.resolve( "ModuleConfig.bx" ) ), "BoxExpress not found at " + BOXEXPRESS );
		if ( !moduleService.hasModule( boxexpress ) ) {
			ModuleRecord record = new ModuleRecord( BOXEXPRESS.toString() );
			moduleService.getRegistry().put( boxexpress, record );
			record
			    .loadDescriptor( runtime.getRuntimeContext() )
			    .register( runtime.getRuntimeContext() )
			    .activate( runtime.getRuntimeContext() );
		}
		cache = createCache( "expressSessions" );
		createCache( "expressShared" );
	}

	@DisplayName( "the cache reports a distributed object store, which is how BoxExpress decides it is durable" )
	@Test
	public void testObjectStoreIsDistributed() {
		assertThat( cache.getObjectStore() ).isNotNull();
		assertThat( cache.getObjectStore().isDistributed() ).isTrue();

		cache.set( "viaProvider", "v" );
		assertThat( cache.getObjectStore().get( Key.of( "viaProvider" ) ).rawValue() ).isEqualTo( "v" );
		assertThat( cache.getObjectStore().lookup( Key.of( "viaProvider" ) ) ).isTrue();
		assertThat( cache.getObjectStore().clear( Key.of( "viaProvider" ) ) ).isTrue();
		assertThat( cache.lookup( "viaProvider" ) ).isFalse();
	}

	@DisplayName( "a BoxExpress session written through one app is read back from Couchbase by a second app" )
	@Test
	public void testBoxExpressSessionRoundTrip() {
		// @formatter:off
		runtime.executeSource(
		    """
			function buildApp() {
				var store = boxExpressCacheStore( "expressSessions", { requireDurable: true, fallback: false } );
				var app   = boxExpress();
				app.set( "log", false );
				app.use( boxExpressSession( { store: store, resave: false, saveUninitialized: false } ) );
				app.get( "/write", ( req, res ) => {
					req.session.cart = { "owner" : "rob", "items" : [ 1, 2, 3 ] };
					res.json( { "ok" : true } );
				} );
				app.get( "/read", ( req, res ) => res.json( { "cart" : req.session.cart ?: "MISSING" } ) );
				app.get( "/logout", ( req, res ) => {
					req.destroySession();
					res.json( { "ok" : true } );
				} );
				return { "app" : app, "store" : store };
			}

			nodeA = buildApp();
			nodeB = buildApp();

			durable   = nodeA.store.isDurable();
			storeMode = nodeA.store.getMode();

			written  = nodeA.app.inject( { url: "/write" } );
			readBack = nodeB.app.inject( { url: "/read", cookies: written.cookies } ).json();
			stranger = nodeB.app.inject( { url: "/read" } ).json();

			nodeB.app.inject( { url: "/logout", cookies: written.cookies } );
			afterLogout = nodeA.app.inject( { url: "/read", cookies: written.cookies } ).json();
			""",
		    context
		);
		// @formatter:on
		assertThat( variables.get( Key.of( "durable" ) ) ).isEqualTo( true );
		assertThat( variables.get( Key.of( "storeMode" ) ) ).isEqualTo( "cache" );

		IStruct cart = ( IStruct ) ( ( IStruct ) variables.get( Key.of( "readBack" ) ) ).get( Key.of( "cart" ) );
		assertThat( cart.get( Key.of( "owner" ) ) ).isEqualTo( "rob" );
		assertThat( cart.getAsArray( Key.of( "items" ) ).size() ).isEqualTo( 3 );

		assertThat( ( ( IStruct ) variables.get( Key.of( "stranger" ) ) ).get( Key.of( "cart" ) ) ).isEqualTo( "MISSING" );
		assertThat( ( ( IStruct ) variables.get( Key.of( "afterLogout" ) ) ).get( Key.of( "cart" ) ) ).isEqualTo( "MISSING" );
	}

	@DisplayName( "a BoxExpress session is stored as a prefixed Couchbase document with the session's maxAge as its expiry" )
	@Test
	public void testBoxExpressSessionDocument() {
		// @formatter:off
		runtime.executeSource(
		    """
			app = boxExpress();
			app.set( "log", false );
			app.use( boxExpressSession( { cache: "expressSessions", requireDurable: true, maxAge: 600, resave: false, saveUninitialized: false } ) );
			app.get( "/write", ( req, res ) => {
				req.session.marker = "doc-check";
				res.json( { "id" : req.sessionID } );
			} );
			result = app.inject( { url: "/write" } ).json().id;
			""",
		    context
		);
		// @formatter:on
		String	prefix		= "expresssessions:boxexpress-session-";
		long	deadline	= System.currentTimeMillis() + 5000;
		String	docId		= null;
		while ( docId == null && System.currentTimeMillis() < deadline ) {
			docId = rawCollection.scan( ScanType.prefixScan( prefix ) )
			    .map( r -> r.id() )
			    .filter( id -> ( ( IStruct ) cache.get( id.substring( "expresssessions:".length() ) ).get() ).containsValue( "doc-check" ) )
			    .findFirst()
			    .orElse( null );
		}
		assertThat( docId ).isNotNull();
		long ttl = java.time.Duration.between( java.time.Instant.now(), rawExpiry( docId ).get() ).toSeconds();
		assertThat( ttl ).isAtLeast( 540 );
		assertThat( ttl ).isAtMost( 600 );
	}

	@DisplayName( "BoxExpress sessions work over real HTTP between two listening servers sharing the cache" )
	@Test
	public void testBoxExpressSessionOverHttp() throws Exception {
		variables.put( Key.of( "portA" ), freePort() );
		variables.put( Key.of( "portB" ), freePort() );
		// @formatter:off
		runtime.executeSource(
		    """
			function listenOn( port ) {
				var app = boxExpress();
				app.set( "log", false );
				app.use( boxExpressSession( { cache: "expressSessions", requireDurable: true, fallback: false, resave: false, saveUninitialized: false } ) );
				app.get( "/write", ( req, res ) => {
					req.session.user = "ada";
					res.json( { "ok" : true } );
				} );
				app.get( "/read", ( req, res ) => res.json( { "user" : req.session.user ?: "MISSING" } ) );
				app.listen( port, ( p ) => {}, { block: false } );
				return app;
			}
			appA = listenOn( portA );
			appB = listenOn( portB );
			""",
		    context
		);
		// @formatter:on
		try {
			HttpClient				http		= HttpClient.newHttpClient();
			HttpResponse<String>	written		= http.send(
			    HttpRequest.newBuilder( URI.create( "http://localhost:" + variables.get( Key.of( "portA" ) ) + "/write" ) ).build(),
			    HttpResponse.BodyHandlers.ofString() );
			String					cookie		= written.headers().firstValue( "set-cookie" ).get().split( ";" )[ 0 ];
			HttpResponse<String>	readBack	= http.send(
			    HttpRequest.newBuilder( URI.create( "http://localhost:" + variables.get( Key.of( "portB" ) ) + "/read" ) ).header( "Cookie", cookie ).build(),
			    HttpResponse.BodyHandlers.ofString() );

			assertThat( written.statusCode() ).isEqualTo( 200 );
			assertThat( readBack.statusCode() ).isEqualTo( 200 );
			assertThat( readBack.body() ).contains( "ada" );
		} finally {
			runtime.executeSource( "appA.close(); appB.close();", context );
		}
	}

	@DisplayName( "BoxExpress rate limiting counts across two app instances through the cache" )
	@Test
	public void testBoxExpressRateLimit() {
		// @formatter:off
		runtime.executeSource(
		    """
			function limitedApp() {
				var app = boxExpress();
				app.set( "log", false );
				app.use( boxExpressRateLimit( { max: 3, windowMs: 60000, cache: "expressShared", requireDurable: true, fallback: false } ) );
				app.get( "/", ( req, res ) => res.json( { "ok" : true } ) );
				return app;
			}
			nodeA    = limitedApp();
			nodeB    = limitedApp();
			clientIp = "10.9.8.7";
			statuses = [
				nodeA.inject( { url: "/", ip: clientIp } ).statusCode,
				nodeB.inject( { url: "/", ip: clientIp } ).statusCode,
				nodeA.inject( { url: "/", ip: clientIp } ).statusCode,
				nodeB.inject( { url: "/", ip: clientIp } ).statusCode,
				nodeA.inject( { url: "/", ip: clientIp } ).statusCode
			];
			otherClient = nodeB.inject( { url: "/", ip: "10.9.8.8" } ).statusCode;
			""",
		    context
		);
		// @formatter:on
		assertThat( variables.getAsArray( Key.of( "statuses" ) ) ).containsExactly( 200, 200, 200, 429, 429 ).inOrder();
		assertThat( variables.get( Key.of( "otherClient" ) ) ).isEqualTo( 200 );
	}

	@DisplayName( "BoxExpress cluster mode: peers discover each other, one manager is elected, and a dead peer is replaced" )
	@Test
	public void testBoxExpressCluster() {
		// @formatter:off
		runtime.executeSource(
		    """
			function waitUntil( condition, timeoutMs = 8000 ) {
				var waited = 0;
				while ( !condition() && waited < timeoutMs ) {
					sleep( 50 );
					waited += 50;
				}
				return condition();
			}
			function peer( app, name ) {
				return new bxModules.boxexpress.models.cluster.ClusterManager( app, {
					cluster: { enabled: true, name: name, cacheProvider: "expressShared", peerIdleTimeoutSeconds: 2 }
				} );
			}
			suffix = createUUID();
			nameA  = "ws://node-a-#suffix#:3000";
			nameB  = "ws://node-b-#suffix#:3000";
			appA   = boxExpress();
			appB   = boxExpress();
			try {
				cmA = peer( appA, nameA );
				cmB = peer( appB, nameB );

				seesImmediately = cmA.getPeers().contains( nameB ) && cmB.getPeers().contains( nameA );
				memberCount     = cmA.getClusterMembers().len();

				cmA.getManagerNode();
				cmB.getManagerNode();
				agreed      = waitUntil( () => len( cmA.getManagerNode() ) && cmA.getManagerNode() == cmB.getManagerNode() );
				managerNode = cmA.getManagerNode();
				oneManager  = cmA.isManager() != cmB.isManager();

				cmA.shutdown();
				peerDropped = waitUntil( () => !cmB.getPeers().contains( nameA ) );
				tookOver    = waitUntil( () => cmB.isManager() );
			} finally {
				try { cmA.shutdown(); } catch ( any e ) {}
				try { cmB.shutdown(); } catch ( any e ) {}
				appA.close();
				appB.close();
			}
			""",
		    context
		);
		// @formatter:on
		assertThat( variables.get( Key.of( "seesImmediately" ) ) ).isEqualTo( true );
		assertThat( variables.get( Key.of( "memberCount" ) ) ).isEqualTo( 2 );
		assertThat( variables.get( Key.of( "agreed" ) ) ).isEqualTo( true );
		assertThat( variables.get( Key.of( "managerNode" ) ) ).isEqualTo( variables.get( Key.of( "nameA" ) ) );
		assertThat( variables.get( Key.of( "oneManager" ) ) ).isEqualTo( true );
		assertThat( variables.get( Key.of( "peerDropped" ) ) ).isEqualTo( true );
		assertThat( variables.get( Key.of( "tookOver" ) ) ).isEqualTo( true );
	}

	@DisplayName( "during a Couchbase outage BoxExpress sessions fall back to memory, or answer 500 on a session read with fallback off, and recover afterwards" )
	@Test
	public void testBoxExpressSessionOutage() {
		// @formatter:off
		runtime.executeSource(
		    """
			function sessionApp( fallback ) {
				var app = boxExpress();
				app.set( "log", false );
				app.use( boxExpressSession( { cache: "expressSessions", fallback: fallback, resave: false, saveUninitialized: false } ) );
				app.get( "/write", ( req, res ) => {
					req.session.user = "ada";
					res.json( { "ok" : true } );
				} );
				app.get( "/read", ( req, res ) => res.json( { "user" : req.session.user ?: "MISSING" } ) );
				return app;
			}
			tolerant = sessionApp( true );
			strict   = sessionApp( false );
			before   = tolerant.inject( { url: "/write" } );
			""",
		    context
		);
		duringOutage( () -> runtime.executeSource(
		    """
			tolerantWrite = tolerant.inject( { url: "/write" } );
			tolerantRead  = tolerant.inject( { url: "/read", cookies: tolerantWrite.cookies } );
			strictStatus  = strict.inject( { url: "/read", cookies: before.cookies } ).statusCode;
			""",
		    context
		) );
		runtime.executeSource(
		    """
			afterOld = tolerant.inject( { url: "/read", cookies: before.cookies } ).json().user;
			afterNew = strict.inject( { url: "/read", cookies: strict.inject( { url: "/write" } ).cookies } ).json().user;
			tolerantWriteStatus = tolerantWrite.statusCode;
			tolerantReadUser    = tolerantRead.json().user;
			""",
		    context
		);
		// @formatter:on
		assertThat( variables.get( Key.of( "tolerantWriteStatus" ) ) ).isEqualTo( 200 );
		assertThat( variables.get( Key.of( "tolerantReadUser" ) ) ).isEqualTo( "ada" );
		assertThat( variables.get( Key.of( "strictStatus" ) ) ).isEqualTo( 500 );
		assertThat( variables.get( Key.of( "afterOld" ) ) ).isEqualTo( "ada" );
		assertThat( variables.get( Key.of( "afterNew" ) ) ).isEqualTo( "ada" );
	}

	private static int freePort() throws Exception {
		try ( ServerSocket socket = new ServerSocket( 0 ) ) {
			return socket.getLocalPort();
		}
	}
}
