package com.kisdigital.couchcache;

import static com.google.common.truth.Truth.assertThat;

import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import ortus.boxlang.runtime.context.ScriptingRequestBoxContext;
import ortus.boxlang.runtime.context.SessionBoxContext;
import ortus.boxlang.runtime.scopes.Key;
import ortus.boxlang.runtime.scopes.RequestScope;
import ortus.boxlang.runtime.types.Array;
import ortus.boxlang.runtime.types.IStruct;

public class SessionIntegrationTest extends BaseIntegrationTest {

	static final String APP_DIR = Path.of( "src/test/resources/sessionapp" ).toAbsolutePath().toString();

	@BeforeAll
	public static void createCaches() {
		createCache( "couchSessions", "resetTimeoutOnAccess", true );
	}

	private ScriptingRequestBoxContext request( String template, Key sessionID ) {
		String						path	= Path.of( APP_DIR, template ).toString();
		// The session is created while Application.bx loads, so the ID has to be in place first
		ScriptingRequestBoxContext	ctx		= new ScriptingRequestBoxContext( runtime.getRuntimeContext(), false );
		ctx.setSessionID( sessionID );
		ctx.loadApplicationDescriptor( Path.of( path ).toUri() );
		runtime.executeTemplate( path, ctx );
		// Only the web runtime writes the session back at request end (WebRequestBoxContext); do the same call here
		ctx.getParentOfType( SessionBoxContext.class ).persistSession( ctx );
		return ctx;
	}

	@DisplayName( "session data written in one request is read back from Couchbase in a new request context" )
	@Test
	public void testSessionRoundTrip() {
		Key							sessionID	= Key.of( UUID.randomUUID().toString() );
		String						docId		= "couchsessions:couchsessionapp:" + sessionID.getNameNoCase();

		ScriptingRequestBoxContext	first		= request( "write.bxs", sessionID );
		assertThat( first.getScopeNearby( RequestScope.name ).get( Key.of( "written" ) ) ).isEqualTo( true );
		assertThat( rawExists( docId ) ).isTrue();
		assertThat( rawExpiry( docId ).isPresent() ).isTrue();

		ScriptingRequestBoxContext	second	= request( "read.bxs", sessionID );
		Object						cart	= second.getScopeNearby( RequestScope.name ).get( Key.of( "cart" ) );
		assertThat( cart ).isInstanceOf( IStruct.class );
		assertThat( ( ( IStruct ) cart ).get( Key.of( "owner" ) ) ).isEqualTo( "rob" );
		assertThat( ( ( Array ) ( ( IStruct ) cart ).get( Key.of( "items" ) ) ).size() ).isEqualTo( 3 );

		ScriptingRequestBoxContext stranger = request( "read.bxs", Key.of( UUID.randomUUID().toString() ) );
		assertThat( stranger.getScopeNearby( RequestScope.name ).get( Key.of( "cart" ) ) ).isEqualTo( "MISSING" );
	}
}
