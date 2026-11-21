package com.kisdigital.couchcache;

import java.util.stream.Stream;

import ortus.boxlang.runtime.cache.ICacheEntry;
import ortus.boxlang.runtime.cache.filters.ICacheKeyFilter;
import ortus.boxlang.runtime.cache.providers.ICacheProvider;
import ortus.boxlang.runtime.cache.store.AbstractStore;
import ortus.boxlang.runtime.cache.store.IObjectStore;
import ortus.boxlang.runtime.scopes.Key;
import ortus.boxlang.runtime.types.IStruct;
import ortus.boxlang.runtime.types.Struct;

/**
 * A view of the provider as an IObjectStore. The provider doesn't use it: it exists so that
 * callers asking "is this cache shared?" via getObjectStore().isDistributed() (BoxExpress's
 * session store and cluster manager do) get a true answer instead of a null store.
 * Everything delegates to the provider's quiet methods, except clearAll(), which announces as usual.
 */
public class CouchObjectStore extends AbstractStore {

	private CouchCacheProvider couch;

	@Override
	public IObjectStore init( ICacheProvider provider, IStruct config ) {
		this.provider		= provider;
		this.couch			= ( CouchCacheProvider ) provider;
		this.config			= config;
		this.distributed	= true;
		return this;
	}

	@Override
	public void shutdown() {
		// The provider owns the connection
	}

	@Override
	public int flush() {
		return 0;
	}

	@Override
	public void evict() {
		// Couchbase owns expiry
	}

	@Override
	public int getSize() {
		return this.couch.getSize();
	}

	@Override
	public void clearAll() {
		this.couch.clearAll();
	}

	@Override
	public boolean clearAll( ICacheKeyFilter filter ) {
		return this.couch.clearAll( filter );
	}

	@Override
	public boolean clear( Key key ) {
		return this.couch.clearQuiet( key.getName() );
	}

	@Override
	public IStruct clear( Key... keys ) {
		IStruct results = new Struct();
		for ( Key key : keys ) {
			results.put( key, clear( key ) );
		}
		return results;
	}

	@Override
	public Key[] getKeys() {
		return getKeysStream().toArray( Key[]::new );
	}

	@Override
	public Key[] getKeys( ICacheKeyFilter filter ) {
		return getKeysStream( filter ).toArray( Key[]::new );
	}

	@Override
	public Stream<Key> getKeysStream() {
		return this.couch.getKeysStream().map( Key::of );
	}

	@Override
	public Stream<Key> getKeysStream( ICacheKeyFilter filter ) {
		return getKeysStream().filter( filter );
	}

	@Override
	public boolean lookup( Key key ) {
		return this.couch.lookupQuiet( key.getName() );
	}

	@Override
	public IStruct lookup( Key... keys ) {
		IStruct results = new Struct();
		for ( Key key : keys ) {
			results.put( key, lookup( key ) );
		}
		return results;
	}

	@Override
	public IStruct lookup( ICacheKeyFilter filter ) {
		return lookup( getKeys( filter ) );
	}

	@Override
	public ICacheEntry get( Key key ) {
		return this.couch.fetch( key.getName() );
	}

	@Override
	public IStruct get( Key... keys ) {
		IStruct results = new Struct();
		for ( Key key : keys ) {
			results.put( key, get( key ) );
		}
		return results;
	}

	@Override
	public IStruct get( ICacheKeyFilter filter ) {
		return get( getKeys( filter ) );
	}

	@Override
	public ICacheEntry getQuiet( Key key ) {
		return get( key );
	}

	@Override
	public IStruct getQuiet( Key... keys ) {
		return get( keys );
	}

	@Override
	public IStruct getQuiet( ICacheKeyFilter filter ) {
		return get( filter );
	}

	@Override
	public void set( Key key, ICacheEntry entry ) {
		this.couch.setQuiet( key, entry );
	}

	@Override
	public void set( IStruct entries ) {
		entries.forEach( ( key, entry ) -> set( key, ( ICacheEntry ) entry ) );
	}
}
