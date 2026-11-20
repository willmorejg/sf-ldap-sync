package com.acmeinsurance.auth.testsupport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

/** Starts a {@link TestDirectory} for the Quarkus tests and points the app at it. */
public final class LdapTestResource implements QuarkusTestResourceLifecycleManager {

    private static TestDirectory directory;
    private Path keyDir;

    public static TestDirectory directory() {
        return directory;
    }

    @Override
    public Map<String, String> start() {
        try {
            directory = new TestDirectory();
            keyDir = Files.createTempDirectory("auth-keys");
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
        return Map.of(
                "auth.ldap.url", directory.url(),
                "auth.ldap.bind-dn", TestDirectory.ADMIN_DN,
                "auth.ldap.bind-password", TestDirectory.ADMIN_PW,
                "auth.issuer", "http://auth.test",
                "auth.signing.key-file", keyDir.resolve("signing-key.pem").toString(),
                // A random port, so the tests run while the compose auth service holds 8081.
                "quarkus.http.test-port", "0");
    }

    @Override
    public void stop() {
        if (directory != null) {
            directory.close();
        }
        try (var files = Files.walk(keyDir)) {
            files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (final Exception e) {
            // best effort
        }
    }
}
