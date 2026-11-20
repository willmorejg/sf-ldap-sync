package com.acmeinsurance.auth;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/** Settings under {@code auth.*} (see application.properties). */
@ConfigMapping(prefix = "auth")
public interface AuthConfig {

    /** The {@code iss} of every token; the public base URL of this service, without a trailing slash. */
    String issuer();

    /** The {@code aud} of access tokens: what resource servers check for. */
    @WithDefault("acmeinsurance")
    String audience();

    /** Which user types' access tokens carry which grant claims. */
    Claims claims();

    Ldap ldap();

    Tokens tokens();

    Signing signing();

    /**
     * Per docs/LDAP_Schema_Definition.md: internal users are scoped by features (plus
     * optional accounts), agents by accounts (plus occasional features), members by
     * policy numbers, and "other" users by any of the three.
     */
    interface Claims {
        @WithDefault("agent,internal,other")
        List<String> featuresFor();

        @WithDefault("agent,internal,other")
        List<String> accountsFor();

        @WithDefault("member,other")
        List<String> policiesFor();
    }

    interface Ldap {
        String url();

        @WithDefault("cn=svc-auth,ou=services,dc=acmeinsurance,dc=com")
        String bindDn();

        String bindPassword();

        @WithDefault("4")
        int poolSize();

        @WithDefault("dc=acmeinsurance,dc=com")
        String baseDn();
    }

    interface Tokens {
        @WithDefault("PT15M")
        Duration accessTtl();

        /** Each refresh issues a new refresh token valid this long ... */
        @WithDefault("PT8H")
        Duration refreshTtl();

        /** ... but never beyond this long after the user last entered a password. */
        @WithDefault("PT24H")
        Duration sessionMax();

        @WithDefault("PT1M")
        Duration codeTtl();

        /** How long the login page stays valid. */
        @WithDefault("PT10M")
        Duration loginTtl();
    }

    interface Signing {
        /** PKCS#8 PEM RSA private key. */
        String keyFile();

        /** Create the key file on first start if it does not exist. */
        @WithDefault("true")
        boolean generate();

        /** Public keys (PEM, X.509 SubjectPublicKeyInfo) still published in the JWKS after a key rotation. */
        Optional<List<String>> previousPublicKeyFiles();
    }
}
