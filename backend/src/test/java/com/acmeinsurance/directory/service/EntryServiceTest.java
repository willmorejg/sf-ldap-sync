package com.acmeinsurance.directory.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import com.acmeinsurance.directory.db.AuditLog;
import com.acmeinsurance.directory.db.Database;
import com.acmeinsurance.directory.ldap.Ldap;
import com.acmeinsurance.directory.model.Kinds;
import com.acmeinsurance.directory.testsupport.TestConfig;
import com.acmeinsurance.common.ldap.LdapConnections;
import com.acmeinsurance.common.testsupport.TestDirectory;
import com.acmeinsurance.common.testsupport.TestLdapConfig;
import com.unboundid.ldap.sdk.SearchResultEntry;

class EntryServiceTest {

    private static final String BASE = TestDirectory.BASE;
    private static final String AGENCY = "cn=AG0042,ou=agencies,ou=accounts," + BASE;
    private static final String AGREEMENT = "cn=AGR-0042-01," + AGENCY;
    private static final String SECOND_COMPANY = "cn=Second Mutual Underwriters,ou=underwritingCompanies," + BASE;
    private static final String SF_AGENT = "uid=sf.agent,ou=agents,ou=people," + BASE;

    @TempDir
    Path tmp;

    private TestDirectory dir;
    private LdapConnections connections;
    private Ldap ldap;
    private AuditLog audit;
    private EntryService entries;
    private Session session;

    @BeforeEach
    void setUp() throws Exception {
        dir = new TestDirectory(TestDirectory.ALL_SAMPLES);
        start(false);
        session = new Session("s1", Instant.now());
        session.update("csr1", "CSR One", Set.of("view-entries", "edit-entries"), "at", Instant.now(), "rt");
        dir.add("dn: " + SF_AGENT, "objectClass: inetOrgPerson", "objectClass: acmeUser",
                "objectClass: acmeAccountAccess", "objectClass: acmeFeatureAccess", "objectClass: acmeSyncManaged",
                "uid: sf.agent", "cn: SF Agent", "sn: Agent", "acmeUserType: agent", "acmeSource: salesforce",
                "acmeAccountDN: " + AGENCY);
    }

    private void start(final boolean sfManagesCompany) {
        if (connections != null) {
            connections.close();
        }
        final TestConfig cfg = new TestConfig(tmp.resolve("t.db").toString(), sfManagesCompany);
        connections = new LdapConnections(new TestLdapConfig(dir.url()));
        ldap = new Ldap(connections, new TestLdapConfig(dir.url()));
        audit = new AuditLog(new Database(cfg));
        entries = new EntryService(ldap, new Kinds(cfg), audit, cfg);
    }

    @AfterEach
    void tearDown() {
        connections.close();
        dir.close();
    }

    private static Map<String, Object> attrs(final Object... kv) {
        final java.util.LinkedHashMap<String, Object> a = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            a.put((String) kv[i], kv[i + 1]);
        }
        return Map.of("attributes", a);
    }

    @Test
    void createsAMemberAsHandMadeWithTheUserTypeAndAFullName() throws Exception {
        final Map<String, Object> v = entries.create(session, "member", attrs("uid", List.of("jdoe"),
                "givenName", List.of("Jane"), "sn", List.of("Doe"), "acmePolicyNumber", List.of("P-1", "P-2")));
        final String dn = "uid=jdoe,ou=members,ou=people," + BASE;
        assertEquals(dn, v.get("dn"));
        final SearchResultEntry e = dir.get(dn);
        assertEquals("member", e.getAttributeValue("acmeUserType"));
        assertEquals("manual", e.getAttributeValue("acmeSource"));
        assertEquals("Jane Doe", e.getAttributeValue("cn"));
        assertEquals(2, e.getAttributeValues("acmePolicyNumber").length);
        assertTrue(e.hasObjectClass("acmeMemberAccess"));
        assertEquals("create", audit.recent(null, 10).getFirst().get("action"));
    }

    @Test
    void refusesAttributesTheKindDoesNotHaveAndMissingRequiredOnes() {
        assertThrows(ApiException.class, () -> entries.create(session, "member",
                attrs("uid", List.of("x"), "sn", List.of("X"), "acmeAccountDN", List.of(AGENCY))));
        assertThrows(ApiException.class, () -> entries.create(session, "member", attrs("uid", List.of("x"))));
        assertThrows(ApiException.class, () -> entries.create(session, "no-such-kind", attrs()));
    }

    @Test
    void createsAgreementsUnderTheirAgency() throws Exception {
        final Map<String, Object> body = new java.util.HashMap<>(attrs("cn", List.of("AGR-0042-09"),
                "acmeUnderwritingCompanyDN", List.of(SECOND_COMPANY)));
        body.put("parentId", Ids.encode(AGENCY));
        entries.create(session, "agreement", body);
        final SearchResultEntry e = dir.get("cn=AGR-0042-09," + AGENCY);
        assertEquals(SECOND_COMPANY, e.getAttributeValue("acmeUnderwritingCompanyDN"));
        final Map<String, Object> list = entries.list("agreement", null, Ids.encode(AGENCY));
        assertEquals(3, ((List<?>) list.get("items")).size());
    }

    @Test
    void anAgreementNeedsAnAgencyParent() {
        assertThrows(ApiException.class, () -> entries.create(session, "agreement", attrs("cn", List.of("X"))));
    }

    @Test
    void updateReplacesAndRemovesOnlyTheGivenAttributes() throws Exception {
        final String id = Ids.encode(AGREEMENT);
        entries.update(session, "agreement", id, attrs("displayName", List.of("Property"), "description", List.of()));
        SearchResultEntry e = dir.get(AGREEMENT);
        assertEquals("Property", e.getAttributeValue("displayName"));
        assertNotNull(e.getAttributeValue("acmeUnderwritingCompanyDN"));
        entries.update(session, "agreement", id, attrs("acmeUnderwritingCompanyDN", List.of()));
        e = dir.get(AGREEMENT);
        assertNull(e.getAttributeValue("acmeUnderwritingCompanyDN"));
        assertThrows(ApiException.class, () -> entries.update(session, "agreement", id,
                attrs("cn", List.of("AGR-RENAMED"))));
    }

    @Test
    void salesforceEntriesOnlyAllowTheAttributesTheSyncLeavesAlone() throws Exception {
        final String id = Ids.encode(SF_AGENT);
        final ApiException e = assertThrows(ApiException.class, () -> entries.update(session, "agent", id,
                attrs("mail", List.of("a@example.com"))));
        assertEquals(409, e.status());
        entries.update(session, "agent", id,
                attrs("acmeFeatureDN", List.of("cn=view-entries,cn=directory,ou=features," + BASE)));
        assertEquals(1, dir.get(SF_AGENT).getAttributeValues("acmeFeatureDN").length);
        assertEquals(409, assertThrows(ApiException.class, () -> entries.delete(session, "agent", id)).status());
        final Map<String, Object> view = entries.get("agent", id);
        assertEquals("salesforce", view.get("managedBy"));
        assertEquals(List.of("acmeFeatureDN"), view.get("editable"));
        assertEquals(false, view.get("deletable"));
    }

    @Test
    void theUnderwritingCompanyOfASalesforceAgreementIsReadOnlyWhenTheSyncManagesIt() throws Exception {
        dir.modify("dn: " + AGREEMENT, "changetype: modify", "add: objectClass", "objectClass: acmeSyncManaged", "-",
                "add: acmeSource", "acmeSource: salesforce");
        final String id = Ids.encode(AGREEMENT);
        final Map<String, Object> change = attrs("acmeUnderwritingCompanyDN", List.of(SECOND_COMPANY));
        entries.update(session, "agreement", id, change);
        assertEquals(SECOND_COMPANY, dir.get(AGREEMENT).getAttributeValue("acmeUnderwritingCompanyDN"));
        start(true);
        assertThrows(ApiException.class, () -> entries.update(session, "agreement", id,
                attrs("acmeUnderwritingCompanyDN", List.of())));
    }

    @Test
    void deletesHandMadeLeavesButNotParents() throws Exception {
        assertThrows(Exception.class, () -> entries.delete(session, "agency", Ids.encode(AGENCY)));
        final String book = "cn=BK-000340,cn=AGR-0042-02," + AGENCY;
        entries.delete(session, "book", Ids.encode(book));
        assertNull(dir.get(book));
    }

    @Test
    void anEntryIsOnlyFoundAsItsOwnKind() {
        assertEquals(404, assertThrows(ApiException.class,
                () -> entries.get("agency", Ids.encode(AGREEMENT))).status());
        assertThrows(ApiException.class, () -> entries.get("agreement", "not-base64!"));
    }

    @Test
    void locksUnlocksAndResetsPasswords() throws Exception {
        final String id = Ids.encode(SF_AGENT);
        entries.lock(session, "agent", id);
        assertEquals("000001010000Z", dir.get(SF_AGENT).getAttributeValue("pwdAccountLockedTime"));
        entries.unlock(session, "agent", id);
        assertFalse(dir.get(SF_AGENT).hasAttribute("pwdAccountLockedTime"));

        final Map<String, Object> reset = entries.resetPassword(session, "agent", id);
        final String temp = (String) reset.get("temporaryPassword");
        assertTrue(temp.matches("[A-Za-z0-9]{4}(-[A-Za-z0-9]{4}){3}"), temp);
        final SearchResultEntry e = dir.get(SF_AGENT);
        assertEquals("TRUE", e.getAttributeValue("pwdReset"));
        assertEquals(temp, e.getAttributeValue("userPassword"));
        assertFalse(audit.recent(null, 10).toString().contains(temp));

        assertThrows(ApiException.class, () -> entries.lock(session, "agency", Ids.encode(AGENCY)));
    }

    @Test
    void aDeactivatedPersonCannotBeUnlockedHere() throws Exception {
        dir.modify("dn: " + SF_AGENT, "changetype: modify", "add: acmeDeactivated", "acmeDeactivated: 20261101000000Z",
                "-", "add: pwdAccountLockedTime", "pwdAccountLockedTime: 000001010000Z");
        assertEquals(409, assertThrows(ApiException.class,
                () -> entries.unlock(session, "agent", Ids.encode(SF_AGENT))).status());
    }

    @Test
    void listsSearchesAndLooksUp() throws Exception {
        final Map<String, Object> agents = entries.list("agent", "sf", null);
        assertEquals(1, ((List<?>) agents.get("items")).size());
        final List<Map<String, Object>> found = entries.lookup("agency,agreement", "0042");
        assertEquals(3, found.size());
        assertEquals("AG0042", found.getFirst().get("label"));
        assertEquals("AG0042 / AGR-0042-01", found.get(1).get("label"));
        assertEquals(11, entries.kinds().size());
    }
}
