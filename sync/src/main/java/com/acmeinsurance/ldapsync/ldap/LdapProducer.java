package com.acmeinsurance.ldapsync.ldap;

import java.security.GeneralSecurityException;

import com.acmeinsurance.ldapsync.SyncConfig;
import com.unboundid.ldap.sdk.LDAPException;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

@ApplicationScoped
public class LdapProducer {

    /** Created on first use, so the app still starts while OpenLDAP is down. */
    @Produces
    @Singleton
    LdapDirectory directory(final SyncConfig cfg) throws LDAPException, GeneralSecurityException {
        final SyncConfig.Ldap l = cfg.ldap();
        return new LdapDirectory(l.url(), l.bindDn(), l.bindPassword(), l.poolSize(), cfg.safety().dryRun());
    }

    void close(@Disposes final LdapDirectory directory) {
        directory.close();
    }
}
