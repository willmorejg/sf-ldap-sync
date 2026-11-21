package com.acmeinsurance.sync.ldap;

import java.security.GeneralSecurityException;

import com.acmeinsurance.common.ldap.LdapConfig;
import com.acmeinsurance.common.ldap.LdapConnections;
import com.acmeinsurance.sync.SyncConfig;
import com.unboundid.ldap.sdk.LDAPException;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/** The sync's view of the directory: a pool bound as svc-sync, honouring dry-run mode. */
@ApplicationScoped
public class LdapProducer {

    /** Created on first use, so the app still starts while OpenLDAP is down. */
    @Produces
    @Singleton
    LdapDirectory directory(final LdapConnections connections, final LdapConfig ldap, final SyncConfig cfg)
            throws LDAPException, GeneralSecurityException {
        return new LdapDirectory(connections.pool(ldap.sync()), cfg.safety().dryRun());
    }

    void close(@Disposes final LdapDirectory directory) {
        directory.close();
    }
}
