package com.acmeinsurance.sync.csv;

import static com.acmeinsurance.common.testsupport.TestDirectory.FEATURES;
import static com.acmeinsurance.common.testsupport.TestDirectory.INTERNAL;
import static com.acmeinsurance.common.testsupport.TestDirectory.MEMBERS;
import static com.acmeinsurance.common.testsupport.TestDirectory.OTHER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.acmeinsurance.common.ldap.DnUtil;
import com.acmeinsurance.sync.SyncConfig;
import com.acmeinsurance.sync.ldap.LdapDirectory;
import com.acmeinsurance.common.testsupport.TestDirectory;
import com.unboundid.ldap.sdk.SearchResultEntry;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;

/** CSV intake per user type, against the in-memory directory. */
final class CsvIntakeServiceTest {

    private TestDirectory ldap;
    private LdapDirectory dir;
    private CsvIntakeService csv;

    @BeforeEach
    void setUp() throws Exception {
        ldap = new TestDirectory(TestDirectory.SYNC_SAMPLES);
        ldap.add("dn: cn=100234,ou=clients,ou=accounts,dc=acmeinsurance,dc=com", "objectClass: acmeClientAccount",
                "cn: 100234");
        dir = new LdapDirectory(ldap.pool(), false);
        final SyncConfig cfg = new SmallRyeConfigBuilder()
                .withMapping(SyncConfig.class)
                .withSources(new PropertiesConfigSource(
                        Map.of("sync.accounts.soql", "x", "sync.agents.soql", "x"), "test", 500))
                .build().getConfigMapping(SyncConfig.class);
        csv = new CsvIntakeService(cfg, () -> dir);
    }

    @AfterEach
    void tearDown() {
        dir.close();
        ldap.close();
    }

    private Map<String, Object> load(final String file, final String... lines) throws Exception {
        final String text = String.join("\n", lines) + "\n";
        return csv.load(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), file);
    }

    private static Set<String> values(final SearchResultEntry e, final String attr) {
        final Set<String> out = new TreeSet<>();
        final String[] v = e.getAttributeValues(attr);
        if (v != null) {
            for (final String s : v) {
                out.add(attr.endsWith("DN") ? DnUtil.normalize(s) : s);
            }
        }
        return out;
    }

    @Test
    void internalUsersGetFeatureGrantsAndOptionalAccountScope() throws Exception {
        load("internal/staff.csv", "uid,sn,features,accounts",
                "carol,Csr,claims|quoting/read-quotes,client:100234");

        final SearchResultEntry e = ldap.get("uid=carol," + INTERNAL);
        assertEquals("csv", e.getAttributeValue("acmeSource"));
        assertEquals("internal", e.getAttributeValue("acmeUserType"));
        assertTrue(e.hasObjectClass("acmeFeatureAccess"));
        assertEquals(Set.of(DnUtil.normalize("cn=claims," + FEATURES),
                DnUtil.normalize("cn=read-quotes,cn=quoting," + FEATURES)), values(e, "acmeFeatureDN"));
        assertEquals(1, values(e, "acmeAccountDN").size());
    }

    @Test
    void aMalformedFeatureReferenceFailsTheRow() {
        // A reference to a feature that does not exist is refused by the constraint
        // overlay in production (smoke test); the in-memory server has no such overlay.
        assertThrows(IllegalStateException.class,
                () -> load("internal/staff.csv", "uid,sn,features", "dave,Bad,a/b/c"));
        assertNull(get("uid=dave," + INTERNAL));
    }

    @Test
    void membersHoldPolicyNumbersOnly() throws Exception {
        load("members/m.csv", "uid,sn,policyNumbers", "m1,One,POL-1|POL-2");
        assertEquals(Set.of("POL-1", "POL-2"), values(ldap.get("uid=m1," + MEMBERS), "acmePolicyNumber"));

        assertThrows(IllegalStateException.class,
                () -> load("members/m.csv", "uid,sn,accounts", "m2,Two,client:100234"));
        assertThrows(IllegalStateException.class,
                () -> load("members/m.csv", "uid,sn,features", "m3,Three,quoting"));
        assertNull(get("uid=m2," + MEMBERS));
        assertNull(get("uid=m3," + MEMBERS));
    }

    @Test
    void internalUsersDoNotHoldPolicyNumbers() {
        assertThrows(IllegalStateException.class,
                () -> load("internal/staff.csv", "uid,sn,policyNumbers", "erin,Staff,POL-1"));
    }

    @Test
    void otherUsersMayHoldAllThreeKindsOfGrant() throws Exception {
        load("other/o.csv", "uid,sn,features,accounts,policyNumbers",
                "casey,Contractor,claims/view-claims,client:100234,POL-9");

        final SearchResultEntry e = ldap.get("uid=casey," + OTHER);
        assertNotNull(e);
        assertEquals("csv", e.getAttributeValue("acmeSource"));
        assertEquals(1, values(e, "acmeFeatureDN").size());
        assertEquals(1, values(e, "acmeAccountDN").size());
        assertEquals(Set.of("POL-9"), values(e, "acmePolicyNumber"));
    }

    @Test
    void takesOverEntriesFromTheOldPerFeedSource() throws Exception {
        ldap.add("dn: uid=m4," + MEMBERS, "objectClass: inetOrgPerson", "objectClass: acmeUser",
                "objectClass: acmeMemberAccess", "objectClass: acmeSyncManaged", "uid: m4", "cn: Old", "sn: Four",
                "acmeUserType: member", "acmeSource: csv-members");

        load("members/m.csv", "uid,sn,cn", "m4,Four,New Name");

        final SearchResultEntry e = ldap.get("uid=m4," + MEMBERS);
        assertEquals("csv", e.getAttributeValue("acmeSource"));
        assertEquals("New Name", e.getAttributeValue("cn"));
    }

    private SearchResultEntry get(final String dn) {
        try {
            return ldap.get(dn);
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
