package com.acmeinsurance.directory.ldap;

import java.security.GeneralSecurityException;
import java.util.List;

import com.acmeinsurance.common.ldap.LdapConfig;
import com.acmeinsurance.common.ldap.LdapConnections;
import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.LDAPConnectionPool;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.LDAPSearchException;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ResultCode;
import com.unboundid.ldap.sdk.SearchRequest;
import com.unboundid.ldap.sdk.SearchResult;
import com.unboundid.ldap.sdk.SearchResultEntry;
import com.unboundid.ldap.sdk.SearchScope;
import com.unboundid.ldap.sdk.schema.Schema;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The directory, as svc-directory. The connection pool opens on first use, so the
 * app starts (and serves its sign-in page) while OpenLDAP is still coming up.
 */
@ApplicationScoped
public class Ldap {

    /** One page of a search: the entries, and whether the size limit cut it short. */
    public record Page(List<SearchResultEntry> entries, boolean truncated) {
    }

    private static final int SEARCH_TIME_LIMIT_SECONDS = 15;

    private final LdapConnections connections;
    private final LdapConfig ldap;
    private LDAPConnectionPool pool;

    public Ldap(final LdapConnections connections, final LdapConfig ldap) {
        this.connections = connections;
        this.ldap = ldap;
    }

    public String baseDn() {
        return ldap.baseDn();
    }

    public SearchResultEntry get(final String dn, final String... attributes) throws LDAPException {
        return pool().getEntry(dn, attributes);
    }

    /** A search that returns what it found so far, flagged as truncated, when it hits the size limit. */
    public Page search(final String base, final SearchScope scope, final Filter filter, final int sizeLimit,
                       final String... attributes) throws LDAPException {
        final SearchRequest request = new SearchRequest(base, scope, filter, attributes);
        request.setSizeLimit(sizeLimit);
        request.setTimeLimitSeconds(SEARCH_TIME_LIMIT_SECONDS);
        try {
            final SearchResult r = pool().search(request);
            return new Page(r.getSearchEntries(), false);
        } catch (final LDAPSearchException e) {
            if (e.getResultCode() == ResultCode.SIZE_LIMIT_EXCEEDED
                    || e.getResultCode() == ResultCode.TIME_LIMIT_EXCEEDED) {
                return new Page(e.getSearchEntries(), true);
            }
            throw e;
        }
    }

    public void add(final Entry entry) throws LDAPException {
        pool().add(entry);
    }

    public void modify(final String dn, final List<Modification> mods) throws LDAPException {
        pool().modify(dn, mods);
    }

    public void delete(final String dn) throws LDAPException {
        pool().delete(dn);
    }

    public Schema schema() throws LDAPException {
        return pool().getSchema();
    }

    private synchronized LDAPConnectionPool pool() throws LDAPException {
        if (pool == null) {
            try {
                pool = connections.pool(ldap.directory());
            } catch (final GeneralSecurityException e) {
                throw new LDAPException(ResultCode.CONNECT_ERROR, "TLS setup failed: " + e.getMessage(), e);
            }
        }
        return pool;
    }

    /** The pool itself is closed by LdapConnections when the app stops. */
    @PreDestroy
    public synchronized void close() {
        pool = null;
    }
}
