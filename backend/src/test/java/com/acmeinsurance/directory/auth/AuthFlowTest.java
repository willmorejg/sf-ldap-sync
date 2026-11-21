package com.acmeinsurance.directory.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jose4j.jwt.JwtClaims;
import org.junit.jupiter.api.Test;

class AuthFlowTest {

    @Test
    void returnToMustBeALocalPath() {
        assertEquals("/people/agent", AuthFlow.localPath("/people/agent"));
        assertEquals("/", AuthFlow.localPath(null));
        assertEquals("/", AuthFlow.localPath("https://evil.example/"));
        assertEquals("/", AuthFlow.localPath("//evil.example/"));
        assertEquals("/", AuthFlow.localPath("/\\evil.example"));
        assertEquals("/", AuthFlow.localPath("/auth/login"));
    }

    @Test
    void readsTheSessionCookie() {
        assertEquals("abc", AuthFlow.sessionId("other=1; idir_session=abc; x=y"));
        assertNull(AuthFlow.sessionId("idir_session="));
        assertNull(AuthFlow.sessionId(null));
        assertNull(AuthFlow.sessionId("idir_sessionx=abc"));
    }

    @Test
    void permissionsComeFromTheDirectoryFeatureOnly() {
        final JwtClaims c = new JwtClaims();
        c.setClaim("features", Map.of("directory", List.of("view-entries", "run-queries"),
                "claims", List.of("edit-entries")));
        assertEquals(Set.of("view-entries", "run-queries"), AuthFlow.permissions(c));
        assertEquals(Set.of(), AuthFlow.permissions(new JwtClaims()));
    }
}
