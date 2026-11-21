package com.kisdigital.couchcache;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.LoggerFactory;
import org.testcontainers.couchbase.BucketDefinition;
import org.testcontainers.couchbase.CouchbaseContainer;
import org.testcontainers.couchbase.CouchbaseService;

import com.couchbase.client.core.error.DocumentNotFoundException;
import com.couchbase.client.java.Bucket;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.Collection;
import com.couchbase.client.java.codec.RawBinaryTranscoder;
import com.couchbase.client.java.kv.GetOptions;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ortus.boxlang.runtime.BoxRuntime;
import ortus.boxlang.runtime.cache.providers.ICacheProvider;
import ortus.boxlang.runtime.context.IBoxContext;
import ortus.boxlang.runtime.context.ScriptingRequestBoxContext;
import ortus.boxlang.runtime.modules.ModuleRecord;
import ortus.boxlang.runtime.scopes.IScope;
import ortus.boxlang.runtime.scopes.Key;
import ortus.boxlang.runtime.scopes.VariablesScope;
import ortus.boxlang.runtime.services.ModuleService;
import ortus.boxlang.runtime.types.IStruct;
import ortus.boxlang.runtime.types.Struct;

/**
 * Loads the built module (build/module) into a BoxLang runtime and starts one
 * Couchbase Server container shared by every test class in the JVM.
 */
public abstract class BaseIntegrationTest {

	protected static final String				BUCKET		= "cache";
	protected static final String				IMAGE		= System.getenv().getOrDefault( "COUCHBASE_IMAGE", "couchbase/server:community-8.0.2" );

	protected static final CouchbaseContainer	couchbase	= new CouchbaseContainer( IMAGE )
	    .withEnabledServices( CouchbaseService.KV )
	    .withBucket( new BucketDefinition( BUCKET ).withPrimaryIndex( false ) );

	protected static BoxRuntime					runtime;
	protected static ModuleService				moduleService;
	protected static ModuleRecord				moduleRecord;
	protected static Collection					rawCollection;
	protected static Key						result		= new Key( "result" );
	protected static Key						moduleName	= new Key( "couchcache" );
	protected ScriptingRequestBoxContext		context;
	protected IScope							variables;

	@BeforeAll
	public static void setup() {
		runtime			= BoxRuntime.getInstance( true, Path.of( "src/test/resources/boxlang.json" ).toString() );
		moduleService	= runtime.getModuleService();
		if ( !couchbase.isRunning() ) {
			for ( String noisy : new String[] { "org.testcontainers", "com.github.dockerjava", "tc", "com.couchbase" } ) {
				( ( Logger ) LoggerFactory.getLogger( noisy ) ).setLevel( Level.INFO );
			}
			couchbase.start();
			Bucket bucket = Cluster
			    .connect( couchbase.getConnectionString(), couchbase.getUsername(), couchbase.getPassword() )
			    .bucket( BUCKET );
			// The bucket is created moments before this; let it warm up so the provider's own 10s connectTimeout isn't racing it
			bucket.waitUntilReady( Duration.ofSeconds( 60 ) );
			rawCollection = bucket.defaultCollection();
		}
		loadModule( runtime.getRuntimeContext() );
	}

	@BeforeEach
	public void setupEach() {
		context		= new ScriptingRequestBoxContext();
		variables	= context.getScopeNearby( VariablesScope.name );
	}

	protected static void loadModule( IBoxContext context ) {
		if ( !runtime.getModuleService().hasModule( moduleName ) ) {
			String physicalPath = Paths.get( "./build/module" ).toAbsolutePath().toString();
			moduleRecord = new ModuleRecord( physicalPath );

			moduleService.getRegistry().put( moduleName, moduleRecord );

			moduleRecord
			    .loadDescriptor( context )
			    .register( context )
			    .activate( context );
		}
	}

	/** Creates (once) a CouchCache-backed cache through the runtime's CacheService. */
	protected static ICacheProvider createCache( String name, Object... extraProperties ) {
		Key cacheName = Key.of( name );
		if ( runtime.getCacheService().hasCache( cacheName ) ) {
			return runtime.getCacheService().getCache( cacheName );
		}
		IStruct props = Struct.of(
		    "connectionString", couchbase.getConnectionString(),
		    "username", couchbase.getUsername(),
		    "password", couchbase.getPassword(),
		    "bucket", BUCKET
		);
		props.putAll( Struct.of( extraProperties ) );
		return runtime.getCacheService().createCache( cacheName, Key.of( "CouchCache" ), props );
	}

	protected static boolean rawExists( String docId ) {
		return rawCollection.exists( docId ).exists();
	}

	/** The document's expiry as Couchbase reports it, or null when the document is missing. Empty = no expiry. */
	protected static Optional<Instant> rawExpiry( String docId ) {
		try {
			return rawCollection
			    .get( docId, GetOptions.getOptions().withExpiry( true ).transcoder( RawBinaryTranscoder.INSTANCE ) )
			    .expiryTime();
		} catch ( DocumentNotFoundException e ) {
			return null;
		}
	}

	/** Freezes the Couchbase container (sockets stay open, nothing answers) while the action runs. */
	protected static void duringOutage( Runnable action ) {
		couchbase.getDockerClient().pauseContainerCmd( couchbase.getContainerId() ).exec();
		try {
			action.run();
		} finally {
			couchbase.getDockerClient().unpauseContainerCmd( couchbase.getContainerId() ).exec();
		}
	}

	protected static void sleep( long millis ) {
		try {
			Thread.sleep( millis );
		} catch ( InterruptedException e ) {
			Thread.currentThread().interrupt();
			throw new RuntimeException( e );
		}
	}
}
