package com.acmeinsurance.ldapsync.ldap;

import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.net.SocketFactory;

import org.jboss.logging.Logger;

import com.unboundid.asn1.ASN1OctetString;
import com.unboundid.ldap.sdk.DN;
import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.LDAPConnection;
import com.unboundid.ldap.sdk.LDAPConnectionOptions;
import com.unboundid.ldap.sdk.LDAPConnectionPool;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.LDAPURL;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ResultCode;
import com.unboundid.ldap.sdk.SearchRequest;
import com.unboundid.ldap.sdk.SearchResult;
import com.unboundid.ldap.sdk.SearchResultEntry;
import com.unboundid.ldap.sdk.SearchScope;
import com.unboundid.ldap.sdk.controls.SimplePagedResultsControl;
import com.unboundid.util.ssl.HostNameSSLSocketVerifier;
import com.unboundid.util.ssl.JVMDefaultTrustManager;
import com.unboundid.util.ssl.SSLUtil;

/**
 * Thin wrapper over an UnboundID connection pool. Every write goes through
 * {@link #add}, {@link #modify}, {@link #modifyDn} or {@link #delete}, so dry-run
 * mode only has to be handled here.
 */
public final class LdapDirectory implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(LdapDirectory.class);
    private static final int PAGE_SIZE = 500;

    private final LDAPConnectionPool pool;
    private final boolean dryRun;

    public LdapDirectory(final String url, final String bindDn, final String bindPassword, final int poolSize,
                         final boolean dryRun)
            throws LDAPException, GeneralSecurityException {
        final LDAPURL u = new LDAPURL(url);
        final LDAPConnectionOptions options = new LDAPConnectionOptions();
        options.setConnectTimeoutMillis(10_000);
        options.setResponseTimeoutMillis(60_000);
        options.setSSLSocketVerifier(new HostNameSSLSocketVerifier(true));
        final SocketFactory sf = "ldaps".equalsIgnoreCase(u.getScheme())
                ? new SSLUtil(JVMDefaultTrustManager.getInstance()).createSSLSocketFactory()
                : SocketFactory.getDefault();
        final LDAPConnection first = new LDAPConnection(sf, options, u.getHost(), u.getPort(), bindDn, bindPassword);
        this.pool = new LDAPConnectionPool(first, 1, Math.max(1, poolSize));
        this.pool.setRetryFailedOperationsDueToInvalidConnections(true);
        this.dryRun = dryRun;
        if (dryRun) {
            LOG.warn("DRY RUN: directory writes are logged, not applied");
        }
    }

    public boolean isDryRun() {
        return dryRun;
    }

    // ---- reads -------------------------------------------------------------

    /** Returns the entry at {@code dn}, or null if it does not exist. */
    public SearchResultEntry get(final String dn, final String... attributes) throws LDAPException {
        return pool.getEntry(dn, attributes);
    }

    /** Returns at most one entry; throws if the search matches more than one. */
    public SearchResultEntry findOne(final String baseDn, final SearchScope scope, final Filter filter,
                                     final String... attributes)
            throws LDAPException {
        return pool.searchForEntry(baseDn, scope, filter, attributes);
    }

    /**
     * Returns every matching entry, using the simple paged results control.
     * OpenLDAP ties the paging cookie to one connection, so all pages are read on
     * the same pooled connection.
     */
    public List<SearchResultEntry> searchAll(final String baseDn, final SearchScope scope, final Filter filter,
                                             final String... attributes)
            throws LDAPException {
        final List<SearchResultEntry> out = new ArrayList<>();
        final LDAPConnection conn = pool.getConnection();
        boolean healthy = false;
        try {
            ASN1OctetString cookie = null;
            do {
                final SearchRequest req = new SearchRequest(baseDn, scope, filter, attributes);
                req.addControl(new SimplePagedResultsControl(PAGE_SIZE, cookie));
                final SearchResult res = conn.search(req);
                out.addAll(res.getSearchEntries());
                final SimplePagedResultsControl page = SimplePagedResultsControl.get(res);
                cookie = page != null && page.moreResultsToReturn() ? page.getCookie() : null;
            } while (cookie != null);
            healthy = true;
            return out;
        } finally {
            if (healthy) {
                pool.releaseConnection(conn);
            } else {
                pool.releaseDefunctConnection(conn);
            }
        }
    }

    // ---- writes ------------------------------------------------------------

    public void add(final Entry entry) throws LDAPException {
        if (dryRun) {
            LOG.infof("[dry-run] add %s%n%s", entry.getDN(), entry.toLDIFString());
            return;
        }
        pool.add(entry);
    }

    public void modify(final String dn, final List<Modification> mods) throws LDAPException {
        if (mods.isEmpty()) {
            return;
        }
        if (dryRun) {
            LOG.infof("[dry-run] modify %s %s", dn, mods);
            return;
        }
        pool.modify(dn, mods);
    }

    /** Renames and/or moves an entry (and its subtree). newSuperiorDn may be null. */
    public void modifyDn(final String dn, final String newRdn, final String newSuperiorDn) throws LDAPException {
        if (dryRun) {
            LOG.infof("[dry-run] modrdn %s -> %s under %s", dn, newRdn, newSuperiorDn);
            return;
        }
        pool.modifyDN(dn, newRdn, true, newSuperiorDn);
    }

    public void delete(final String dn) throws LDAPException {
        if (dryRun) {
            LOG.infof("[dry-run] delete %s", dn);
            return;
        }
        pool.delete(dn);
    }

    // ---- helpers -----------------------------------------------------------

    /** True when {@code dn} is strictly below {@code baseDn}. Invalid DNs are never in scope. */
    public static boolean isUnder(final String dn, final String baseDn) {
        try {
            return new DN(dn).isDescendantOf(new DN(baseDn), false);
        } catch (final LDAPException e) {
            return false;
        }
    }

    /** Normalized form for comparing DNs; falls back to lower case for invalid input. */
    public static String normalize(final String dn) {
        try {
            return new DN(dn).toNormalizedString();
        } catch (final LDAPException e) {
            return dn.toLowerCase(Locale.ROOT);
        }
    }

    public static boolean sameDn(final String a, final String b) {
        return normalize(a).equals(normalize(b));
    }

    /** Number of RDNs; used to delete children before parents. */
    public static int depth(final String dn) {
        try {
            return new DN(dn).getRDNs().length;
        } catch (final LDAPException e) {
            return 0;
        }
    }

    public static boolean isResult(final LDAPException e, final ResultCode code) {
        return e.getResultCode() == code;
    }

    @Override
    public void close() {
        pool.close();
    }
}
