package com.kisdigital.couchcache;

import static com.google.common.truth.Truth.assertThat;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A script run with `java -jar boxlang` that ends while its cache is still registered, the way a migration or cron script does.
 */
public class ScriptExitIntegrationTest extends BaseIntegrationTest {

	@DisplayName( "a script that leaves its cache open exits cleanly, with nothing printed by a shutdown hook" )
	@Test
	public void testScriptExit() throws Exception {
		Path	appDir	= Path.of( "build/script-exit-app" ).toAbsolutePath();
		Path	modules	= appDir.resolve( "boxlang_modules" );
		Path	output	= appDir.resolve( "script.log" );
		Files.createDirectories( modules );
		Files.copy( Path.of( "src/test/resources/scriptexit/script.bxs" ), appDir.resolve( "script.bxs" ), StandardCopyOption.REPLACE_EXISTING );
		Files.deleteIfExists( modules.resolve( "couchcache" ) );
		Files.createSymbolicLink( modules.resolve( "couchcache" ), Path.of( "build/module" ).toAbsolutePath() );

		File			jar		= new File( ortus.boxlang.runtime.BoxRuntime.class.getProtectionDomain().getCodeSource().getLocation().toURI() );
		ProcessBuilder	builder	= new ProcessBuilder(
		    Path.of( System.getProperty( "java.home" ), "bin", "java" ).toString(), "-jar", jar.getAbsolutePath(), "script.bxs" );
		builder.directory( appDir.toFile() );
		builder.redirectErrorStream( true );
		builder.redirectOutput( output.toFile() );
		builder.environment().put( "CB_CONNECTION", couchbase.getConnectionString() );
		builder.environment().put( "CB_USERNAME", couchbase.getUsername() );
		builder.environment().put( "CB_PASSWORD", couchbase.getPassword() );
		builder.environment().put( "CB_BUCKET", BUCKET );
		builder.environment().put( "BOXLANG_HOME", appDir.resolve( "home" ).toString() );

		Process script = builder.start();
		try {
			assertThat( script.waitFor( 120, TimeUnit.SECONDS ) ).isTrue();
		} finally {
			script.destroyForcibly();
		}
		String printed = Files.readString( output );
		assertThat( printed ).contains( "VALUE=ok" );
		assertThat( printed ).doesNotContain( "Exception" );
		assertThat( script.exitValue() ).isEqualTo( 0 );
	}
}
