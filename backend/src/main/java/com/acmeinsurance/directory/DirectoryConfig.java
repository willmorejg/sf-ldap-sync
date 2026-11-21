package com.acmeinsurance.directory;

import java.util.Optional;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/** Settings under {@code directory.*} (application.properties). */
@ConfigMapping(prefix = "directory")
public interface DirectoryConfig {

    /**
     * Base URL users reach the app at; the OAuth redirect URI is {publicUrl}/auth/callback. The same as
     * auth.issuer, except in development, where the Angular dev server (port 4200) serves the app.
     */
    String publicUrl();

    Auth auth();

    Db db();

    Query query();

    /**
     * Whether the Salesforce sync manages agreements' underwriting companies
     * (sync.accounts.underwriting-company-field is set). When it does, the
     * company of a Salesforce agreement is read-only here.
     */
    @WithDefault("false")
    boolean salesforceManagesUnderwritingCompany();

    /** InsureDirectory as an OAuth client of the token service in this app (auth.*). */
    interface Auth {
        @WithDefault("insure-directory")
        String clientId();

        Optional<String> clientSecret();

        /** Sign-in session lifetime here; the token service also ends sessions (session-max). */
        @WithDefault("PT8H")
        java.time.Duration sessionMax();

        @WithDefault("PT30M")
        java.time.Duration idleTimeout();
    }

    interface Db {
        /** SQLite file holding saved queries and the audit log. */
        String path();
    }

    interface Query {
        @WithDefault("500")
        int maxResults();

        @WithDefault("200")
        int listLimit();
    }
}
