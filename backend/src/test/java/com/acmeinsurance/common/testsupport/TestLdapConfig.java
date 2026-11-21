package com.acmeinsurance.common.testsupport;

import java.time.Duration;
import java.util.Optional;

import com.acmeinsurance.common.ldap.LdapConfig;

/** An LdapConfig for plain unit tests: every module binds to the test directory as its manager. */
public record TestLdapConfig(String url) implements LdapConfig {

    @Override
    public String baseDn() {
        return TestDirectory.BASE;
    }

    @Override
    public Duration connectTimeout() {
        return Duration.ofSeconds(5);
    }

    @Override
    public Duration responseTimeout() {
        return Duration.ofSeconds(30);
    }

    @Override
    public Account auth() {
        return admin();
    }

    @Override
    public Account sync() {
        return admin();
    }

    @Override
    public Account directory() {
        return admin();
    }

    private static Account admin() {
        return new Account() {
            @Override
            public String bindDn() {
                return TestDirectory.ADMIN_DN;
            }

            @Override
            public Optional<String> bindPassword() {
                return Optional.of(TestDirectory.ADMIN_PW);
            }

            @Override
            public int poolSize() {
                return 2;
            }
        };
    }
}
