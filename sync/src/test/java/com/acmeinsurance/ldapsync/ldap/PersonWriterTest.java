package com.acmeinsurance.ldapsync.ldap;

import static com.acmeinsurance.ldapsync.testsupport.TestDirectory.AGENCIES;
import static com.acmeinsurance.ldapsync.testsupport.TestDirectory.AGENTS;
import static com.acmeinsurance.ldapsync.testsupport.TestDirectory.AGENT_POLICY;
import static com.acmeinsurance.ldapsync.testsupport.TestDirectory.CLIENTS;
import static com.acmeinsurance.ldapsync.testsupport.TestDirectory.FEATURES;
import static com.acmeinsurance.ldapsync.testsupport.TestDirectory.INTERNAL;
import static com.acmeinsurance.ldapsync.testsupport.TestDirectory.MEMBERS;
import static com.acmeinsurance.ldapsync.testsupport.TestDirectory.PEOPLE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.acmeinsurance.ldapsync.core.Constants;
import com.acmeinsurance.ldapsync.core.PersonSpec;
import com.acmeinsurance.ldapsync.core.SyncReport;
import com.acmeinsurance.ldapsync.testsupport.TestDirectory;
import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldap.sdk.SearchResultEntry;

final class PersonWriterTest {

    private static final String SF_ID = "005000000000001AAA";
    private static final String AGENCY_A = "cn=AG0001," + AGENCIES;
    private static final String AGENCY_B = "cn=AG0002," + AGENCIES;
    private static final String CLIENT_C = "cn=CL0001," + CLIENTS;

    private TestDirectory ldap;
    private LdapDirectory dir;
    private PersonWriter writer;
    private SyncReport report;

    @BeforeEach
    void setUp() throws Exception {
        ldap = new TestDirectory();
        for (final String dn : List.of(AGENCY_A, AGENCY_B)) {
            ldap.add("dn: " + dn, "objectClass: acmeAgencyAccount", "cn: " + dn.substring(3, dn.indexOf(',')));
        }
        ldap.add("dn: " + CLIENT_C, "objectClass: acmeClientAccount", "cn: CL0001");
        dir = ldap.connect(false);
        writer = new PersonWriter(dir, PEOPLE);
        report = new SyncReport("test");
    }

    @AfterEach
    void tearDown() {
        dir.close();
        ldap.close();
    }

    private static PersonWriter.Profile agents(final boolean adoptUnowned) {
        return new PersonWriter.Profile("salesforce", "agent", AGENTS,
                List.of("inetOrgPerson", "acmeUser", "acmeAccountAccess"), AGENT_POLICY,
                dn -> LdapDirectory.isUnder(dn, AGENCIES), true, adoptUnowned);
    }

    private static PersonSpec agent(final String uid, final boolean active, final Set<String> grants) {
        final Map<String, List<String>> attrs = new LinkedHashMap<>();
        attrs.put("givenName", List.of("Ada"));
        attrs.put("sn", List.of("Lovelace"));
        attrs.put("cn", List.of("Ada Lovelace")); // AgentMapper always fills cn
        attrs.put("mail", List.of("ada@example.com"));
        return new PersonSpec(uid, SF_ID, active, attrs, grants, null);
    }

    private static Set<String> grants(final SearchResultEntry e) {
        final Set<String> out = new TreeSet<>();
        final String[] v = e.getAttributeValues("acmeAccountDN");
        if (v != null) {
            for (final String dn : v) {
                out.add(LdapDirectory.normalize(dn));
            }
        }
        return out;
    }

    private static Set<String> normalized(final String... dns) {
        final Set<String> out = new TreeSet<>();
        for (final String dn : dns) {
            out.add(LdapDirectory.normalize(dn));
        }
        return out;
    }

    @Test
    void createsAnOwnedAgentWithoutAPassword() throws Exception {
        writer.upsert(agent("ada", true, Set.of(AGENCY_A)), agents(false), report);

        final SearchResultEntry e = ldap.get("uid=ada," + AGENTS);
        assertNotNull(e);
        assertTrue(e.hasObjectClass("inetOrgPerson"));
        assertTrue(e.hasObjectClass("acmeSyncManaged"));
        assertEquals("salesforce", e.getAttributeValue("acmeSource"));
        assertEquals("agent", e.getAttributeValue("acmeUserType"));
        assertEquals(SF_ID, e.getAttributeValue("acmeSalesforceId"));
        assertEquals("Ada Lovelace", e.getAttributeValue("cn"));
        assertEquals(normalized(AGENCY_A), grants(e));
        assertEquals(LdapDirectory.normalize(AGENT_POLICY),
                LdapDirectory.normalize(e.getAttributeValue("pwdPolicySubentry")));
        assertFalse(e.hasAttribute("userPassword"));
        assertEquals(1, report.count("agent.created"));
        assertFalse(report.hasErrors());
    }

    @Test
    void secondUpsertWithSameDataChangesNothing() throws Exception {
        writer.upsert(agent("ada", true, Set.of(AGENCY_A)), agents(false), report);
        writer.upsert(agent("ada", true, Set.of(AGENCY_A)), agents(false), report);
        assertEquals(1, report.count("agent.unchanged"));
        assertEquals(0, report.count("agent.updated"));
    }

    @Test
    void renamesWhenTheUsernameChanges() throws Exception {
        writer.upsert(agent("ada", true, Set.of()), agents(false), report);
        writer.upsert(agent("ada.l", true, Set.of()), agents(false), report);

        assertNull(ldap.get("uid=ada," + AGENTS));
        final SearchResultEntry e = ldap.get("uid=ada.l," + AGENTS);
        assertNotNull(e);
        assertEquals("ada.l", e.getAttributeValue("uid"));
        assertEquals(1, report.count("agent.renamed"));
    }

    @Test
    void prunesOnlyAgencyGrantsAndKeepsClientGrants() throws Exception {
        writer.upsert(agent("ada", true, Set.of(AGENCY_A)), agents(false), report);
        // Grants added by hand: one more agency and one client.
        ldap.server().modify("dn: uid=ada," + AGENTS, "changetype: modify", "add: acmeAccountDN",
                "acmeAccountDN: " + AGENCY_B, "acmeAccountDN: " + CLIENT_C);

        writer.upsert(agent("ada", true, Set.of(AGENCY_A)), agents(false), report);

        assertEquals(normalized(AGENCY_A, CLIENT_C), grants(ldap.get("uid=ada," + AGENTS)));
    }

    @Test
    void locksAndUnlocksWithoutDeleting() throws Exception {
        writer.upsert(agent("ada", true, Set.of()), agents(false), report);
        writer.upsert(agent("ada", false, Set.of()), agents(false), report);

        final SearchResultEntry locked = ldap.get("uid=ada," + AGENTS);
        assertEquals(Constants.PERMANENT_LOCK, locked.getAttributeValue("pwdAccountLockedTime"));
        assertTrue(locked.hasAttribute("acmeDeactivated"));
        assertEquals(1, report.count("agent.deactivated"));

        writer.upsert(agent("ada", true, Set.of()), agents(false), report);
        final SearchResultEntry unlocked = ldap.get("uid=ada," + AGENTS);
        assertFalse(unlocked.hasAttribute("pwdAccountLockedTime"));
        assertFalse(unlocked.hasAttribute("acmeDeactivated"));
        assertEquals(1, report.count("agent.reactivated"));
    }

    @Test
    void keepsALockoutSetByThePasswordPolicyWhenReactivating() throws Exception {
        writer.upsert(agent("ada", true, Set.of()), agents(false), report);
        writer.upsert(agent("ada", false, Set.of()), agents(false), report);
        // Simulate ppolicy locking the account after failed binds (a real timestamp, not the permanent lock).
        ldap.server().modify("dn: uid=ada," + AGENTS, "changetype: modify", "replace: pwdAccountLockedTime",
                "pwdAccountLockedTime: 20261119120000Z");

        writer.upsert(agent("ada", true, Set.of()), agents(false), report);

        final SearchResultEntry e = ldap.get("uid=ada," + AGENTS);
        assertFalse(e.hasAttribute("acmeDeactivated"));
        assertEquals("20261119120000Z", e.getAttributeValue("pwdAccountLockedTime"));
    }

    @Test
    void doesNotCreateInactiveUsers() throws Exception {
        writer.upsert(agent("ada", false, Set.of()), agents(false), report);
        assertNull(ldap.get("uid=ada," + AGENTS));
        assertEquals(1, report.count("agent.skippedInactive"));
    }

    @Test
    void leavesHandMadeEntriesAloneUnlessAdoptionIsOn() throws Exception {
        ldap.add("dn: uid=ada," + AGENTS, "objectClass: inetOrgPerson", "uid: ada", "cn: Hand Made", "sn: Made");

        writer.upsert(agent("ada", true, Set.of()), agents(false), report);
        assertTrue(report.hasErrors());
        assertEquals("Hand Made", ldap.get("uid=ada," + AGENTS).getAttributeValue("cn"));

        final SyncReport second = new SyncReport("test");
        writer.upsert(agent("ada", true, Set.of()), agents(true), second);
        final SearchResultEntry e = ldap.get("uid=ada," + AGENTS);
        assertFalse(second.hasErrors(), second.toString());
        assertEquals(1, second.count("agent.adopted"));
        assertEquals("salesforce", e.getAttributeValue("acmeSource"));
        assertEquals("Ada Lovelace", e.getAttributeValue("cn"));
        assertTrue(e.hasObjectClass("acmeSyncManaged"));
    }

    @Test
    void refusesEntriesOwnedByAnotherSource() throws Exception {
        ldap.add("dn: uid=ada," + AGENTS, "objectClass: inetOrgPerson", "objectClass: acmeSyncManaged",
                "uid: ada", "cn: CSV Ada", "sn: Ada", "acmeSource: csv-other");

        writer.upsert(agent("ada", true, Set.of()), agents(true), report);

        assertTrue(report.hasErrors());
        assertEquals("CSV Ada", ldap.get("uid=ada," + AGENTS).getAttributeValue("cn"));
    }

    @Test
    void refusesAUidAlreadyUsedInAnotherOu() throws Exception {
        ldap.add("dn: uid=ada," + MEMBERS, "objectClass: inetOrgPerson", "uid: ada", "cn: Member Ada", "sn: Ada");

        writer.upsert(agent("ada", true, Set.of()), agents(true), report);

        assertTrue(report.hasErrors());
        assertNull(ldap.get("uid=ada," + AGENTS));
    }

    private static PersonWriter.Profile csvInternal(final boolean adoptUnowned) {
        return new PersonWriter.Profile("csv", "internal", INTERNAL,
                List.of("inetOrgPerson", "acmeUser", "acmeFeatureAccess", "acmeAccountAccess"), null,
                dn -> true, true, adoptUnowned);
    }

    private static PersonSpec staff(final String uid, final Set<String> features) {
        final Map<String, List<String>> attrs = new LinkedHashMap<>();
        attrs.put("sn", List.of("Staff"));
        attrs.put("cn", List.of("Some Staff"));
        return new PersonSpec(uid, null, true, attrs, null, null, features);
    }

    private static Set<String> features(final SearchResultEntry e) {
        final Set<String> out = new TreeSet<>();
        final String[] v = e.getAttributeValues("acmeFeatureDN");
        if (v != null) {
            for (final String dn : v) {
                out.add(LdapDirectory.normalize(dn));
            }
        }
        return out;
    }

    @Test
    void writesAndPrunesFeatureGrantsWhenTheSourceSendsThem() throws Exception {
        final String quoting = "cn=quoting," + FEATURES;
        final String approve = "cn=approve-claims,cn=claims," + FEATURES;
        writer.upsert(staff("carol", Set.of(quoting, approve)), csvInternal(false), report);
        final SearchResultEntry e = ldap.get("uid=carol," + INTERNAL);
        assertTrue(e.hasObjectClass("acmeFeatureAccess"));
        assertEquals(normalized(quoting, approve), features(e));

        writer.upsert(staff("carol", Set.of(approve)), csvInternal(false), report);
        assertEquals(normalized(approve), features(ldap.get("uid=carol," + INTERNAL)));

        // A spec without feature grants (null) leaves them alone.
        writer.upsert(staff("carol", null), csvInternal(false), report);
        assertEquals(normalized(approve), features(ldap.get("uid=carol," + INTERNAL)));
        assertFalse(report.hasErrors(), report.toString());
    }

    @Test
    void csvOwnsEntriesWrittenUnderTheOldPerFeedSourceNames() throws Exception {
        ldap.add("dn: uid=dan," + INTERNAL, "objectClass: inetOrgPerson", "objectClass: acmeUser",
                "objectClass: acmeSyncManaged", "uid: dan", "cn: Dan", "sn: Old", "acmeUserType: internal",
                "acmeSource: csv-internal");

        writer.upsert(staff("dan", null), csvInternal(false), report);

        assertFalse(report.hasErrors(), report.toString());
        final SearchResultEntry e = ldap.get("uid=dan," + INTERNAL);
        assertEquals("csv", e.getAttributeValue("acmeSource"));
        assertEquals("Some Staff", e.getAttributeValue("cn"));
    }

    @Test
    void manualEntriesAreHandMadeAndOnlyAdoptedWhenAllowed() throws Exception {
        ldap.add("dn: uid=erin," + INTERNAL, "objectClass: inetOrgPerson", "objectClass: acmeSyncManaged",
                "uid: erin", "cn: Erin By Hand", "sn: Erin", "acmeSource: manual");

        writer.upsert(staff("erin", null), csvInternal(false), report);
        assertTrue(report.hasErrors());
        assertEquals("Erin By Hand", ldap.get("uid=erin," + INTERNAL).getAttributeValue("cn"));

        final SyncReport second = new SyncReport("test");
        writer.upsert(staff("erin", null), csvInternal(true), second);
        assertFalse(second.hasErrors(), second.toString());
        assertEquals(1, second.count("internal.adopted"));
        assertEquals("csv", ldap.get("uid=erin," + INTERNAL).getAttributeValue("acmeSource"));
    }

    @Test
    void dryRunWritesNothing() throws Exception {
        try (LdapDirectory dry = ldap.connect(true)) {
            new PersonWriter(dry, PEOPLE).upsert(agent("ada", true, Set.of(AGENCY_A)), agents(false), report);
        }
        assertNull(ldap.get("uid=ada," + AGENTS));
        assertEquals(1, report.count("agent.created"));
    }

    @Test
    void deactivateIsANoOpWhenAlreadyDeactivated() throws Exception {
        writer.upsert(agent("ada", true, Set.of()), agents(false), report);
        final Entry e = ldap.get("uid=ada," + AGENTS);
        writer.deactivate(ldap.get(e.getDN()), agents(false), report);
        writer.deactivate(ldap.get(e.getDN()), agents(false), report);
        assertEquals(1, report.count("agent.deactivated"));
    }
}
