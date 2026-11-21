package com.acmeinsurance.common.ldap;

import static com.acmeinsurance.common.Constants.LDAPS_SCHEME;

import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;

import javax.net.SocketFactory;

import org.jboss.logging.Logger;

import com.unboundid.ldap.sdk.LDAPConnection;
import com.unboundid.ldap.sdk.LDAPConnectionOptions;
import com.unboundid.ldap.sdk.LDAPConnectionPool;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.LDAPURL;
import com.unboundid.ldap.sdk.ResultCode;
import com.unboundid.ldap.sdk.SimpleBindRequest;
import com.unboundid.ldap.sdk.SingleServerSet;
import com.unboundid.util.ssl.HostNameSSLSocketVerifier;
import com.unboundid.util.ssl.JVMDefaultTrustManager;
import com.unboundid.util.ssl.SSLUtil;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Connections to the directory for every module: one pool per service account
 * (see {@link LdapConfig}), and unbound connections for binding as a user. ldaps://
 * URLs use the JVM's trust store and check the host name.
 *
 * <p>A pool opens, binds and closes its own connections (and re-binds any it
 * re-establishes), so a failed bind leaves no connection open. Modules create their
 * pool on first use, so the app starts while OpenLDAP is down.
 */
@ApplicationScoped
public class LdapConnections {

    private static final Logger LOG = Logger.getLogger(LdapConnections.class);

    private final LdapConfig cfg;
    private final List<LDAPConnectionPool> pools = new ArrayList<>();

    public LdapConnections(final LdapConfig cfg) {
        this.cfg = cfg;
    }

    public String baseDn() {
        return cfg.baseDn();
    }

    /** A new pool bound as this account; closed with the app, or by the caller. */
    public synchronized LDAPConnectionPool pool(final LdapConfig.Account account)
            throws LDAPException, GeneralSecurityException {
        final String password = account.bindPassword().orElseThrow(() -> new LDAPException(
                ResultCode.PARAM_ERROR, "no password configured for " + account.bindDn()));
        final LDAPConnectionPool pool = pool(cfg.url(), account.bindDn(), password, account.poolSize(), options());
        pools.add(pool);
        LOG.infof("Directory pool: %s as %s", cfg.url(), account.bindDn());
        return pool;
    }

    /** An unbound connection, for binding as a user; the caller closes it. */
    public LDAPConnection connect() throws LDAPException, GeneralSecurityException {
        final LDAPURL u = new LDAPURL(cfg.url());
        return new LDAPConnection(socketFactory(u), options(), u.getHost(), u.getPort());
    }

    /** A pool for any URL and account; also used by the tests. */
    public static LDAPConnectionPool pool(final String url, final String bindDn, final String password,
                                          final int size, final LDAPConnectionOptions options)
            throws LDAPException, GeneralSecurityException {
        final LDAPURL u = new LDAPURL(url);
        final LDAPConnectionPool pool = new LDAPConnectionPool(
                new SingleServerSet(u.getHost(), u.getPort(), socketFactory(u), options),
                new SimpleBindRequest(bindDn, password), 1, Math.max(1, size));
        pool.setRetryFailedOperationsDueToInvalidConnections(true);
        return pool;
    }

    private LDAPConnectionOptions options() {
        final LDAPConnectionOptions options = new LDAPConnectionOptions();
        options.setConnectTimeoutMillis((int) cfg.connectTimeout().toMillis());
        options.setResponseTimeoutMillis(cfg.responseTimeout().toMillis());
        options.setSSLSocketVerifier(new HostNameSSLSocketVerifier(true));
        return options;
    }

    private static SocketFactory socketFactory(final LDAPURL u) throws GeneralSecurityException {
        return LDAPS_SCHEME.equalsIgnoreCase(u.getScheme())
                ? new SSLUtil(JVMDefaultTrustManager.getInstance()).createSSLSocketFactory()
                : SocketFactory.getDefault();
    }

    @PreDestroy
    public synchronized void close() {
        pools.forEach(LDAPConnectionPool::close);
        pools.clear();
    }
}
