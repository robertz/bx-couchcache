package com.kisdigital.couchcache;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Two BoxExpress servers in two separate JVMs (the BoxLang jar run with `java -jar`), each loading the built module
 * from its own boxlang_modules folder, sharing one Couchbase bucket. Needs a BoxExpress checkout, like BoxExpressIntegrationTest.
 */
public class TwoJvmIntegrationTest extends BaseIntegrationTest {

	static final HttpClient	http	= HttpClient.newHttpClient();
	static Process			nodeA, nodeB;
	static int				portA, portB;

	@BeforeAll
	public static void startNodes() throws Exception {
		assumeTrue( Files.exists( BoxExpressIntegrationTest.BOXEXPRESS.resolve( "ModuleConfig.bx" ) ), "BoxExpress not found" );

		Path	appDir	= Path.of( "build/two-jvm-app" ).toAbsolutePath();
		Path	modules	= appDir.resolve( "boxlang_modules" );
		Files.createDirectories( modules );
		Files.copy( Path.of( "src/test/resources/twojvm/server.bxs" ), appDir.resolve( "server.bxs" ), java.nio.file.StandardCopyOption.REPLACE_EXISTING );
		link( modules.resolve( "boxexpress" ), BoxExpressIntegrationTest.BOXEXPRESS );
		link( modules.resolve( "couchcache" ), Path.of( "build/module" ).toAbsolutePath() );

		portA	= freePort();
		portB	= freePort();
		// One after the other: two BoxLang runtimes starting at the same instant on one machine intermittently fail to load
		// module jars (seen as "No cache provider registered" and "Cannot load interface class"), whatever the module
		nodeA	= start( appDir, "ws://node-a:" + portA, portA );
		awaitUp( nodeA, portA, appDir );
		nodeB = start( appDir, "ws://node-b:" + portB, portB );
		awaitUp( nodeB, portB, appDir );
	}

	@AfterAll
	public static void stopNodes() {
		Stream.of( nodeA, nodeB ).filter( p -> p != null ).forEach( Process::destroyForcibly );
	}

	@DisplayName( "a session written on one JVM is read on the other" )
	@Test
	public void testSessionAcrossJvms() throws Exception {
		HttpResponse<String>	written	= get( portA, "/write", null );
		String					cookie	= written.headers().firstValue( "set-cookie" ).get().split( ";" )[ 0 ];
		assertThat( written.body() ).contains( "node-a" );

		String readOnB = get( portB, "/read", cookie ).body();
		assertThat( readOnB ).contains( "ada" );
		assertThat( readOnB ).contains( "node-b" );
		assertThat( get( portB, "/read", null ).body() ).contains( "MISSING" );
	}

	@DisplayName( "the rate limit is counted across both JVMs" )
	@Test
	public void testRateLimitAcrossJvms() throws Exception {
		int[] statuses = {
		    get( portA, "/limited", null ).statusCode(),
		    get( portB, "/limited", null ).statusCode(),
		    get( portA, "/limited", null ).statusCode(),
		    get( portB, "/limited", null ).statusCode(),
		    get( portA, "/limited", null ).statusCode()
		};
		assertThat( statuses ).asList().containsExactly( 200, 200, 200, 429, 429 ).inOrder();
	}

	@DisplayName( "both JVMs list each other as cluster members" )
	@Test
	public void testClusterMembersAcrossJvms() throws Exception {
		long	deadline	= System.currentTimeMillis() + 8000;
		String	onA			= "", onB = "";
		while ( System.currentTimeMillis() < deadline ) {
			onA	= get( portA, "/members", null ).body();
			onB	= get( portB, "/members", null ).body();
			if ( onA.contains( "node-b" ) && onB.contains( "node-a" ) ) {
				break;
			}
			sleep( 100 );
		}
		assertThat( onA ).contains( "node-a" );
		assertThat( onA ).contains( "node-b" );
		assertThat( onB ).contains( "node-a" );
		assertThat( onB ).contains( "node-b" );
	}

	@DisplayName( "a key written on one JVM shows up in the other JVM's getKeys(); the report prints how often it lagged" )
	@Test
	public void testScanAcrossJvms() throws Exception {
		int		missedAtFirst	= 0;
		long	worstMs			= 0;
		for ( int i = 0; i < 100; i++ ) {
			String key = "xjvm" + i;
			get( portA, "/put?key=" + key + "&value=" + i, null );
			long start = System.nanoTime();
			if ( !get( portB, "/keys", null ).body().contains( "\"" + key + "\"" ) ) {
				missedAtFirst++;
				long deadline = System.currentTimeMillis() + 5000;
				while ( !get( portB, "/keys", null ).body().contains( "\"" + key + "\"" ) ) {
					assertThat( System.currentTimeMillis() ).isLessThan( deadline );
				}
				worstMs = Math.max( worstMs, ( System.nanoTime() - start ) / 1_000_000 );
			}
		}
		System.out.println( "XJVM-SCAN missedAtFirst=" + missedAtFirst + "/100 worstCatchUpMs=" + worstMs );
	}

	@DisplayName( "bx:lock with cacheName excludes the other JVM while held, and is free afterwards" )
	@Test
	public void testLockAcrossJvms() throws Exception {
		CompletableFuture<String> holder = CompletableFuture.supplyAsync( () -> {
			try {
				return get( portA, "/lock?timeout=5&hold=2500", null ).body();
			} catch ( Exception e ) {
				throw new RuntimeException( e );
			}
		} );
		sleep( 700 );
		assertThat( get( portB, "/lock?timeout=1", null ).body() ).contains( "false" );
		assertThat( holder.get() ).contains( "true" );
		assertThat( get( portB, "/lock?timeout=1", null ).body() ).contains( "true" );
	}

	private static HttpResponse<String> get( int port, String path, String cookie ) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder( URI.create( "http://localhost:" + port + path ) );
		if ( cookie != null ) {
			request.header( "Cookie", cookie );
		}
		return http.send( request.build(), HttpResponse.BodyHandlers.ofString() );
	}

	private static Process start( Path appDir, String name, int port ) throws Exception {
		// The same BoxLang jar this test is running on
		File			jar		= new File( ortus.boxlang.runtime.BoxRuntime.class.getProtectionDomain().getCodeSource().getLocation().toURI() );
		ProcessBuilder	builder	= new ProcessBuilder(
		    Path.of( System.getProperty( "java.home" ), "bin", "java" ).toString(), "-jar", jar.getAbsolutePath(), "server.bxs" );
		builder.directory( appDir.toFile() );
		builder.redirectErrorStream( true );
		builder.redirectOutput( appDir.resolve( "node-" + port + ".log" ).toFile() );
		builder.environment().put( "CB_CONNECTION", couchbase.getConnectionString() );
		builder.environment().put( "CB_USERNAME", couchbase.getUsername() );
		builder.environment().put( "CB_PASSWORD", couchbase.getPassword() );
		builder.environment().put( "CB_BUCKET", BUCKET );
		builder.environment().put( "NODE_NAME", name );
		builder.environment().put( "PORT", String.valueOf( port ) );
		// Its own BoxLang home, so the test never writes to the user's ~/.boxlang
		builder.environment().put( "BOXLANG_HOME", appDir.resolve( "home-" + port ).toString() );
		return builder.start();
	}

	private static void awaitUp( Process node, int port, Path appDir ) throws Exception {
		long deadline = System.currentTimeMillis() + 90000;
		while ( System.currentTimeMillis() < deadline ) {
			if ( !node.isAlive() ) {
				break;
			}
			try {
				if ( get( port, "/read", null ).statusCode() == 200 ) {
					return;
				}
			} catch ( java.io.IOException e ) {
				sleep( 250 );
			}
		}
		throw new IllegalStateException( "node on port " + port + " did not start:\n" + Files.readString( appDir.resolve( "node-" + port + ".log" ) ) );
	}

	private static void link( Path link, Path target ) throws Exception {
		Files.deleteIfExists( link );
		Files.createSymbolicLink( link, target );
	}

	private static int freePort() throws Exception {
		try ( ServerSocket socket = new ServerSocket( 0 ) ) {
			return socket.getLocalPort();
		}
	}
}
