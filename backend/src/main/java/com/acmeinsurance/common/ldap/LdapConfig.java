package com.acmeinsurance.common.ldap;

import java.time.Duration;
import java.util.Optional;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * The directory server and the service account each module binds as ({@code ldap.*}).
 * The accounts stay separate so OpenLDAP's ACLs keep each module to its own rights:
 * svc-auth reads, svc-sync writes what the sources own, svc-directory maintains entries
 * for InsureDirectory.
 */
@ConfigMapping(prefix = "ldap")
public interface LdapConfig {

    String url();

    @WithDefault("dc=acmeinsurance,dc=com")
    String baseDn();

    @WithDefault("PT10S")
    Duration connectTimeout();

    /** Longest wait for one operation; the sync's paged searches of the whole tree need the most. */
    @WithDefault("PT60S")
    Duration responseTimeout();

    /** The token service (read only, plus user binds on separate connections). */
    Account auth();

    /** The Salesforce sync and the CSV intake. */
    Account sync();

    /** InsureDirectory. */
    Account directory();

    interface Account {
        String bindDn();

        /** Unset: the module cannot reach the directory, and says so when it tries. */
        Optional<String> bindPassword();

        @WithDefault("4")
        int poolSize();
    }
}
