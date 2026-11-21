package com.acmeinsurance.directory.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.acmeinsurance.common.web.ApiException;
import com.acmeinsurance.directory.auth.Session;
import com.acmeinsurance.directory.db.Database;
import com.acmeinsurance.directory.db.SavedQueries;
import com.acmeinsurance.directory.ldap.Ldap;
import com.acmeinsurance.directory.testsupport.TestConfig;
import com.acmeinsurance.common.ldap.LdapConnections;
import com.acmeinsurance.common.testsupport.TestDirectory;
import com.acmeinsurance.common.testsupport.TestLdapConfig;

class QueryServiceTest {

    @TempDir
    Path tmp;

    private TestDirectory dir;
    private LdapConnections connections;
    private Ldap ldap;
    private QueryService queries;

    @BeforeEach
    void setUp() throws Exception {
        dir = new TestDirectory(TestDirectory.ALL_SAMPLES);
        final TestConfig cfg = new TestConfig(tmp.resolve("q.db").toString(), false);
        connections = new LdapConnections(new TestLdapConfig(dir.url()));
        ldap = new Ldap(connections, new TestLdapConfig(dir.url()));
        queries = new QueryService(ldap, new SavedQueries(new Database(cfg)), cfg);
    }

    @AfterEach
    void tearDown() {
        connections.close();
        dir.close();
    }

    @Test
    void runsASearchWithoutReturningPasswords() throws Exception {
        final Map<String, Object> r = queries.run(Map.of("baseDn", "ou=services," + TestDirectory.BASE,
                "scope", "one", "filter", "(objectClass=*)"));
        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> entries = (List<Map<String, Object>>) r.get("entries");
        assertFalse(entries.isEmpty());
        assertFalse(entries.toString().toLowerCase(java.util.Locale.ROOT).contains("userpassword"));
    }

    @Test
    void capsTheResultsAndSaysSo() throws Exception {
        final Map<String, Object> r = queries.run(Map.of("baseDn", TestDirectory.BASE, "scope", "sub",
                "filter", "(objectClass=*)", "sizeLimit", 3));
        assertEquals(3, r.get("count"));
        assertEquals(true, r.get("truncated"));
    }

    @Test
    void validatesTheRequest() {
        assertThrows(ApiException.class, () -> queries.run(Map.of("baseDn", "dc=example,dc=com", "filter", "(a=b)")));
        assertThrows(ApiException.class, () -> queries.run(Map.of("baseDn", TestDirectory.BASE, "filter", "(uid=")));
        assertThrows(ApiException.class, () -> queries.run(Map.of("baseDn", TestDirectory.BASE, "scope", "deep",
                "filter", "(uid=x)")));
    }

    @Test
    void savesListsAndDeletesQueries() throws Exception {
        final Session s = new Session("s", Instant.now());
        s.update("dev1", "Dev", Set.of("run-queries"), "at", Instant.now(), null);
        final SavedQueries.SavedQuery q = queries.save(s, Map.of("name", "Agents", "baseDn",
                "ou=people," + TestDirectory.BASE, "scope", "sub", "filter", "(acmeUserType=agent)"));
        assertEquals(1, queries.savedQueries().size());
        assertEquals("dev1", q.createdBy());
        assertThrows(ApiException.class, () -> queries.save(s, Map.of("name", "Bad", "filter", "(")));
        queries.delete(String.valueOf(q.id()));
        assertTrue(queries.savedQueries().isEmpty());
    }

    @Test
    void describesTheSchema() throws Exception {
        final String schema = queries.schema().toString();
        assertTrue(schema.contains("acmeUnderwritingCompanyDN"));
        assertTrue(schema.contains("acmeAgreementAccount"));
    }
}
