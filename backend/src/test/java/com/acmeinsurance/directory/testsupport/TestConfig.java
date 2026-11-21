package com.acmeinsurance.directory.testsupport;

import java.time.Duration;
import java.util.Optional;

import com.acmeinsurance.directory.DirectoryConfig;

/** A DirectoryConfig for plain unit tests: a SQLite file (the directory comes from TestLdapConfig). */
public record TestConfig(String dbPath, boolean salesforceManagesUnderwritingCompany)
        implements DirectoryConfig {

    @Override
    public String publicUrl() {
        return "http://localhost:8080";
    }

    @Override
    public Auth auth() {
        return new Auth() {
            @Override
            public String clientId() {
                return "insure-directory";
            }

            @Override
            public Optional<String> clientSecret() {
                return Optional.empty();
            }

            @Override
            public Duration sessionMax() {
                return Duration.ofHours(8);
            }

            @Override
            public Duration idleTimeout() {
                return Duration.ofMinutes(30);
            }
        };
    }

    @Override
    public Db db() {
        return () -> dbPath;
    }

    @Override
    public Query query() {
        return new Query() {
            @Override
            public int maxResults() {
                return 50;
            }

            @Override
            public int listLimit() {
                return 20;
            }
        };
    }
}
