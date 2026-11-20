package com.acmeinsurance.ldapsync.salesforce;

import static com.acmeinsurance.ldapsync.testsupport.TestDirectory.AGENCIES;
import static com.acmeinsurance.ldapsync.testsupport.TestDirectory.AGENTS;
import static com.acmeinsurance.ldapsync.testsupport.TestDirectory.CLIENTS;
import static com.acmeinsurance.ldapsync.testsupport.TestDirectory.FEATURES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.acmeinsurance.ldapsync.SyncConfig;
import com.acmeinsurance.ldapsync.core.SalesforceIds;
import com.acmeinsurance.ldapsync.ldap.LdapDirectory;
import com.acmeinsurance.ldapsync.ldap.PersonWriter;
import com.acmeinsurance.ldapsync.testsupport.TestDirectory;
import com.unboundid.ldap.sdk.SearchResultEntry;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;

/**
 * Runs the whole Salesforce sync against an in-memory directory loaded with the
 * real schema, with canned Salesforce records in place of the API.
 *
 * <p>Starting data: agencies AG1 and AG2, agreement AGR1 under AG1 and AGR2
 * under AG2, book BK1 under AGR1. Agent ada works for AG1 and has an extra grant
 * to BK1 (AccountContactRelation); agent bob works for AG2.
 */
final class SalesforceSyncServiceTest {

    private static final String AG1 = id("001", 1);
    private static final String AG2 = id("001", 2);
    private static final String AGR1 = id("001", 11);
    private static final String AGR2 = id("001", 12);
    private static final String BK1 = id("001", 21);
    private static final String ADA = id("005", 1);
    private static final String BOB = id("005", 2);
    private static final String ADA_CONTACT = id("003", 1);
    private static final String BOB_CONTACT = id("003", 2);

    private static final String AG1_DN = "cn=AG1," + AGENCIES;
    private static final String AG2_DN = "cn=AG2," + AGENCIES;
    private static final String AGR1_DN = "cn=AGR1," + AG1_DN;
    private static final String AGR2_DN = "cn=AGR2," + AG2_DN;
    private static final String BK1_DN = "cn=BK1," + AGR1_DN;
    private static final String ADA_DN = "uid=ada@example.com," + AGENTS;
    private static final String BOB_DN = "uid=bob@example.com," + AGENTS;

    private TestDirectory ldap;
    private LdapDirectory dir;
    private FakeSalesforce sf;
    private final Map<String, String> props = new HashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        ldap = new TestDirectory();
        dir = ldap.connect(false);
        sf = new FakeSalesforce();
        sf.account(AG1, "AG1", "Agency", null, "First Agency");
        sf.account(AG2, "AG2", "Agency", null, "Second Agency");
        sf.account(AGR1, "AGR1", "Agreement", AG1, null);
        sf.account(AGR2, "AGR2", "Agreement", AG2, null);
        sf.account(BK1, "BK1", "Book", AGR1, "Book one");
        sf.user(ADA, "ada@example.com", "Ada", "Lovelace", ADA_CONTACT, AG1);
        sf.user(BOB, "bob@example.com", "Bob", "Builder", BOB_CONTACT, AG2);
        sf.entitlement(ADA_CONTACT, BK1);

        props.put("sync.ldap.url", "ldap://unused");
        props.put("sync.ldap.bind-dn", "cn=unused");
        props.put("sync.ldap.bind-password", "unused");
        props.put("sync.accounts.soql", "SELECT ... FROM Account");
        props.put("sync.accounts.record-types.Agency", "agency");
        props.put("sync.accounts.record-types.Agreement", "agreement");
        props.put("sync.accounts.record-types.Book", "book");
        props.put("sync.agents.soql", "SELECT ... FROM User");
        props.put("sync.agents.agency-field", "Contact.AccountId");
        props.put("sync.agents.entitlement-soql", "SELECT ContactId, AccountId FROM AccountContactRelation");
        props.put("sync.agents.attributes.givenName", "FirstName");
        props.put("sync.agents.attributes.sn", "LastName");
        props.put("sync.agents.attributes.mail", "Email");
        // Most tests remove 2 of 5 accounts or 1 of 2 agents; the guard has its own test.
        props.put("sync.safety.max-remove-percent", "50");
    }

    @AfterEach
    void tearDown() {
        dir.close();
        ldap.close();
    }

    // ---- tests -------------------------------------------------------------

    @Test
    void buildsTheAccountTreeAndAgents() throws Exception {
        final Map<String, Object> report = sync();

        assertNoErrors(report);
        for (final String dn : List.of(AG1_DN, AG2_DN, AGR1_DN, AGR2_DN, BK1_DN)) {
            assertNotNull(ldap.get(dn), dn);
        }
        final SearchResultEntry book = ldap.get(BK1_DN);
        assertTrue(book.hasObjectClass("acmeBookAccount"));
        assertTrue(book.hasObjectClass("acmeSyncManaged"));
        assertEquals(BK1, book.getAttributeValue("acmeSalesforceId"));
        assertEquals("salesforce", book.getAttributeValue("acmeSource"));
        assertEquals("Book one", book.getAttributeValue("displayName"));

        final SearchResultEntry ada = ldap.get(ADA_DN);
        assertNotNull(ada);
        assertEquals(ADA, ada.getAttributeValue("acmeSalesforceId"));
        assertEquals("Ada Lovelace", ada.getAttributeValue("cn"));
        assertEquals(dns(AG1_DN, BK1_DN), grants(ADA_DN));
        assertEquals(dns(AG2_DN), grants(BOB_DN));
        assertEquals(5, count(report, "accounts.created"));
        assertEquals(2, count(report, "agent.created"));
    }

    @Test
    void secondRunChangesNothing() throws Exception {
        sync();
        final Map<String, Object> report = sync();

        assertNoErrors(report);
        assertEquals(5, count(report, "accounts.unchanged"));
        assertEquals(2, count(report, "agent.unchanged"));
        assertEquals(0, count(report, "accounts.created"));
        assertEquals(0, count(report, "agent.updated"));
    }

    @Test
    void movesABookToItsNewAgreementAndUpdatesGrants() throws Exception {
        sync();
        sf.account(BK1, "BK1", "Book", AGR2, "Book one");
        final Map<String, Object> report = sync();

        assertNoErrors(report);
        final String moved = "cn=BK1," + AGR2_DN;
        assertNull(ldap.get(BK1_DN));
        assertEquals(BK1, ldap.get(moved).getAttributeValue("acmeSalesforceId"));
        assertEquals(1, count(report, "accounts.moved"));
        assertEquals(dns(AG1_DN, moved), grants(ADA_DN));
    }

    @Test
    void renamingAnAgencyMovesItsSubtree() throws Exception {
        sync();
        sf.account(AG1, "AG1X", "Agency", null, "First Agency");
        final Map<String, Object> report = sync();

        assertNoErrors(report);
        final String agency = "cn=AG1X," + AGENCIES;
        final String book = "cn=BK1,cn=AGR1," + agency;
        assertNull(ldap.get(AG1_DN));
        assertNotNull(ldap.get(book));
        assertEquals(1, count(report, "accounts.renamed"));
        assertEquals(dns(agency, book), grants(ADA_DN));
    }

    @Test
    void deletesAccountsGoneFromSalesforceChildrenFirst() throws Exception {
        sync();
        sf.remove(AGR1);
        sf.remove(BK1);
        final Map<String, Object> report = sync();

        assertNoErrors(report);
        assertNull(ldap.get(AGR1_DN));
        assertNull(ldap.get(BK1_DN));
        assertNotNull(ldap.get(AG1_DN));
        assertEquals(2, count(report, "accounts.deleted"));
        assertEquals(dns(AG1_DN), grants(ADA_DN));
    }

    @Test
    void neverTouchesClientGrants() throws Exception {
        final String client = "cn=CL0001," + CLIENTS;
        ldap.add("dn: " + client, "objectClass: acmeClientAccount", "cn: CL0001");
        sync();
        ldap.server().modify("dn: " + ADA_DN, "changetype: modify", "add: acmeAccountDN",
                "acmeAccountDN: " + client, "acmeAccountDN: " + AG2_DN);

        final Map<String, Object> report = sync();

        assertNoErrors(report);
        // The hand-added agency grant is pruned, the client grant stays.
        assertEquals(dns(AG1_DN, BK1_DN, client), grants(ADA_DN));
    }

    @Test
    void neverTouchesFeatureGrants() throws Exception {
        sync();
        assertTrue(ldap.get(ADA_DN).hasObjectClass("acmeFeatureAccess"));
        final String perm = "cn=read-quotes,cn=quoting," + FEATURES;
        ldap.server().modify("dn: " + ADA_DN, "changetype: modify", "add: acmeFeatureDN", "acmeFeatureDN: " + perm);

        assertNoErrors(sync());

        assertEquals(LdapDirectory.normalize(perm),
                LdapDirectory.normalize(ldap.get(ADA_DN).getAttributeValue("acmeFeatureDN")));
    }

    @Test
    void locksAgentsWhoDisappearInsteadOfDeletingThem() throws Exception {
        sync();
        sf.remove(BOB);
        Map<String, Object> report = sync();

        assertNoErrors(report);
        final SearchResultEntry bob = ldap.get(BOB_DN);
        assertNotNull(bob);
        assertEquals(PersonWriter.PERMANENT_LOCK, bob.getAttributeValue("pwdAccountLockedTime"));
        assertTrue(bob.hasAttribute("acmeDeactivated"));
        assertEquals(1, count(report, "agent.deactivated"));

        // Coming back in Salesforce unlocks the entry.
        sf.user(BOB, "bob@example.com", "Bob", "Builder", BOB_CONTACT, AG2);
        report = sync();
        assertNoErrors(report);
        assertFalse(ldap.get(BOB_DN).hasAttribute("acmeDeactivated"));
        assertEquals(1, count(report, "agent.reactivated"));
    }

    @Test
    void anEmptySourceRemovesNothing() throws Exception {
        sync();
        sf.clear();
        final Map<String, Object> report = sync();

        assertTrue(errors(report).stream().anyMatch(e -> e.startsWith("accounts not removed")), errors(report).toString());
        assertTrue(errors(report).stream().anyMatch(e -> e.startsWith("agents not deactivated")), errors(report).toString());
        assertNotNull(ldap.get(BK1_DN));
        assertFalse(ldap.get(ADA_DN).hasAttribute("acmeDeactivated"));
    }

    @Test
    void refusesToRemoveMoreThanTheLimitInOneRun() throws Exception {
        props.put("sync.safety.max-remove-percent", "10");
        sync();
        sf.remove(BK1);
        final Map<String, Object> report = sync();

        assertTrue(errors(report).stream().anyMatch(e -> e.contains("above the 10% limit")), errors(report).toString());
        assertNotNull(ldap.get(BK1_DN));
    }

    @Test
    void leavesAHandMadeAccountAloneAndSkipsItsChildren() throws Exception {
        ldap.add("dn: " + AG1_DN, "objectClass: acmeAgencyAccount", "cn: AG1");
        final Map<String, Object> report = sync();

        assertTrue(errors(report).stream().anyMatch(e -> e.contains("created by hand")), errors(report).toString());
        final SearchResultEntry agency = ldap.get(AG1_DN);
        assertNull(agency.getAttributeValue("acmeSource"));
        assertNull(ldap.get(AGR1_DN));
        assertNull(ldap.get(BK1_DN));
        assertNotNull(ldap.get(AGR2_DN));
    }

    @Test
    void adoptsAHandMadeAccountWhenAllowed() throws Exception {
        ldap.add("dn: " + AG1_DN, "objectClass: acmeAgencyAccount", "cn: AG1");
        props.put("sync.safety.adopt-unowned", "true");
        final Map<String, Object> report = sync();

        assertNoErrors(report);
        final SearchResultEntry agency = ldap.get(AG1_DN);
        assertEquals("salesforce", agency.getAttributeValue("acmeSource"));
        assertEquals(AG1, agency.getAttributeValue("acmeSalesforceId"));
        assertTrue(agency.hasObjectClass("acmeSyncManaged"));
        assertEquals(1, count(report, "accounts.adopted"));
        assertNotNull(ldap.get(BK1_DN));
    }

    @Test
    void dryRunWritesNothing() throws Exception {
        props.put("sync.safety.dry-run", "true");
        try (LdapDirectory dry = ldap.connect(true)) {
            final Map<String, Object> report = service(dry).fullSync();
            assertNoErrors(report);
            assertEquals(5, count(report, "accounts.created"));
        }
        assertNull(ldap.get(AG1_DN));
        assertNull(ldap.get(ADA_DN));
    }

    // ---- helpers -----------------------------------------------------------

    private Map<String, Object> sync() {
        return service(dir).fullSync();
    }

    private SalesforceSyncService service(final LdapDirectory directory) {
        final SmallRyeConfig config = new SmallRyeConfigBuilder()
                .withMapping(SyncConfig.class)
                .withSources(new PropertiesConfigSource(props, "test", 500))
                .build();
        return new SalesforceSyncService(config.getConfigMapping(SyncConfig.class), sf, () -> directory);
    }

    private static String id(final String prefix, final int n) {
        return SalesforceIds.to18(prefix + String.format("%012d", n));
    }

    private Set<String> grants(final String dn) throws Exception {
        final SearchResultEntry e = ldap.get(dn);
        final String[] values = e.getAttributeValues("acmeAccountDN");
        return values == null ? Set.of() : dns(values);
    }

    private static Set<String> dns(final String... dns) {
        final Set<String> out = new TreeSet<>();
        for (final String dn : dns) {
            out.add(LdapDirectory.normalize(dn));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static int count(final Map<String, Object> report, final String key) {
        return ((Map<String, Integer>) report.get("counts")).getOrDefault(key, 0);
    }

    @SuppressWarnings("unchecked")
    private static List<String> errors(final Map<String, Object> report) {
        return (List<String>) report.get("errors");
    }

    private static void assertNoErrors(final Map<String, Object> report) {
        assertTrue(errors(report).isEmpty(), () -> "unexpected errors: " + errors(report));
    }

    /** Canned query results, keyed by record ID so tests can change or remove records. */
    static final class FakeSalesforce implements SalesforceReader {

        private final Map<String, Map<String, Object>> accounts = new LinkedHashMap<>();
        private final Map<String, Map<String, Object>> users = new LinkedHashMap<>();
        private final List<Map<String, Object>> entitlements = new ArrayList<>();

        void account(final String id, final String number, final String recordType, final String parentId,
                     final String name) {
            final Map<String, Object> r = new HashMap<>();
            r.put("Id", id);
            r.put("AccountNumber", number);
            r.put("Name", name);
            r.put("ParentId", parentId);
            r.put("RecordType", Map.of("DeveloperName", recordType));
            accounts.put(id, r);
        }

        void user(final String id, final String username, final String first, final String last, final String contactId,
                  final String agencyId) {
            final Map<String, Object> r = new HashMap<>();
            r.put("Id", id);
            r.put("Username", username);
            r.put("FirstName", first);
            r.put("LastName", last);
            r.put("Email", username);
            r.put("IsActive", true);
            r.put("ContactId", contactId);
            r.put("Contact", Map.of("AccountId", agencyId));
            users.put(id, r);
        }

        void entitlement(final String contactId, final String accountId) {
            entitlements.add(Map.of("ContactId", contactId, "AccountId", accountId));
        }

        void remove(final String id) {
            accounts.remove(id);
            users.remove(id);
        }

        void clear() {
            accounts.clear();
            users.clear();
            entitlements.clear();
        }

        @Override
        public List<Map<String, Object>> query(final String soql) {
            if (soql.contains("AccountContactRelation")) {
                return List.copyOf(entitlements);
            }
            if (soql.contains("FROM Account")) {
                return List.copyOf(accounts.values());
            }
            if (soql.contains("FROM User")) {
                return List.copyOf(users.values());
            }
            throw new IllegalArgumentException("unexpected query: " + soql);
        }
    }
}
