package com.acmeinsurance.common.testsupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

/**
 * Starts a {@link TestDirectory} with every sample for the Quarkus tests and points all three service
 * accounts of the app at it (as the directory manager). Declared on one test class without
 * restrictToAnnotatedClass, so it applies to every @QuarkusTest and the app starts once.
 */
public final class LdapTestResource implements QuarkusTestResourceLifecycleManager {

    public static final String ISSUER = "http://auth.test";

    private static TestDirectory directory;
    private Path keyDir;

    public static TestDirectory directory() {
        return directory;
    }

    @Override
    public Map<String, String> start() {
        try {
            directory = new TestDirectory(TestDirectory.ALL_SAMPLES);
            keyDir = Files.createTempDirectory("auth-keys");
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
        return Map.ofEntries(
                Map.entry("ldap.url", directory.url()),
                Map.entry("ldap.auth.bind-dn", TestDirectory.ADMIN_DN),
                Map.entry("ldap.auth.bind-password", TestDirectory.ADMIN_PW),
                Map.entry("ldap.sync.bind-dn", TestDirectory.ADMIN_DN),
                Map.entry("ldap.sync.bind-password", TestDirectory.ADMIN_PW),
                Map.entry("ldap.directory.bind-dn", TestDirectory.ADMIN_DN),
                Map.entry("ldap.directory.bind-password", TestDirectory.ADMIN_PW),
                Map.entry("auth.issuer", ISSUER),
                Map.entry("auth.signing.key-file", keyDir.resolve("signing-key.pem").toString()),
                Map.entry("directory.auth.client-secret", TestDirectory.DIRECTORY_CLIENT_SECRET));
    }

    @Override
    public void stop() {
        if (directory != null) {
            directory.close();
        }
        if (keyDir == null) {
            return;
        }
        try (Stream<Path> files = Files.walk(keyDir)) {
            files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (final IOException e) {
            // best effort: a temp directory
        }
    }
}
