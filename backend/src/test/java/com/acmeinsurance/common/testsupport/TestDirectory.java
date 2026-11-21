package com.acmeinsurance.common.testsupport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import com.unboundid.ldap.listener.interceptor.InMemoryInterceptedExtendedResult;
import com.unboundid.ldap.listener.interceptor.InMemoryInterceptedSimpleBindResult;
import com.unboundid.ldap.listener.interceptor.InMemoryOperationInterceptor;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.BindResult;
import com.unboundid.ldap.sdk.Control;
import com.unboundid.ldap.sdk.DN;
import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldap.sdk.LDAPConnectionPool;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;
import com.unboundid.ldap.sdk.ResultCode;
import com.unboundid.ldap.sdk.SearchResultEntry;
import com.unboundid.ldap.sdk.experimental.DraftBeheraLDAPPasswordPolicy10ErrorType;
import com.unboundid.ldap.sdk.experimental.DraftBeheraLDAPPasswordPolicy10ResponseControl;
import com.unboundid.ldap.sdk.extensions.PasswordModifyExtendedRequest;
import com.unboundid.ldap.sdk.schema.Schema;
import com.unboundid.ldif.LDIFReader;
import com.unboundid.util.StaticUtils;

import com.acmeinsurance.common.ldap.LdapConnections;

/**
 * An UnboundID in-memory directory set up like the real OpenLDAP server, for every module's tests.
 * The three Acme Insurance schema LDIFs from openldap/ldif are converted on the fly (olcAttributeTypes
 * to attributeTypes, olcObjectClasses to objectClasses), so the tests always run against the schema
 * that ships. The tree comes from 20-dit.ldif plus the sample files a test asks for ({@link #SYNC_SAMPLES}
 * or {@link #ALL_SAMPLES}). Entries under ou=policies are skipped, because UnboundID has no ppolicy schema;
 * the ppolicy attributes the app reads and writes are defined here. Referential integrity covers the
 * DN-valued grants, as the refint overlay does in production. There are no ACLs: tests bind as the
 * directory manager.
 *
 * <p>pwdAccountLockedTime is GeneralizedTime in OpenLDAP, which accepts the special "000001010000Z"
 * (permanent lock). UnboundID's parser rejects year 0000, so the test schema declares it as a string.
 *
 * <p>A small interceptor stands in for the parts of ppolicy the token service relies on: a bind as a
 * user with pwdReset=TRUE gets the changeAfterReset response control, and a password modify clears
 * pwdReset and sets pwdChangedTime.
 */
public final class TestDirectory implements AutoCloseable {

    public static final String BASE = "dc=acmeinsurance,dc=com";
    public static final String PEOPLE = "ou=people," + BASE;
    public static final String AGENTS = "ou=agents," + PEOPLE;
    public static final String MEMBERS = "ou=members," + PEOPLE;
    public static final String INTERNAL = "ou=internal," + PEOPLE;
    public static final String OTHER = "ou=other," + PEOPLE;
    public static final String ACCOUNTS = "ou=accounts," + BASE;
    public static final String AGENCIES = "ou=agencies," + ACCOUNTS;
    public static final String CLIENTS = "ou=clients," + ACCOUNTS;
    public static final String FEATURES = "ou=features," + BASE;
    public static final String UNDERWRITING_COMPANIES = "ou=underwritingCompanies," + BASE;
    public static final String AGENT_POLICY = "cn=agents,ou=policies," + BASE;
    public static final String ADMIN_DN = "cn=Directory Manager";
    public static final String ADMIN_PW = "test-only-admin";
    /** Secret of the sample confidential client demo-app. */
    public static final String DEMO_APP_SECRET = "test-only-demo-app-secret-0123456789";
    /** Secret of the InsureDirectory client (insure-directory). */
    public static final String DIRECTORY_CLIENT_SECRET = "test-only-insure-directory-secret-0123";

    /** What the sync tests start from: no people, accounts or OAuth clients. */
    public static final List<String> SYNC_SAMPLES = List.of(
            "32-sample-features.ldif", "35-sample-underwriting-companies.ldif");
    /** Every sample, the InsureDirectory and sync features, and the OAuth clients. */
    public static final List<String> ALL_SAMPLES = List.of(
            "30-sample-clients.ldif", "35-sample-underwriting-companies.ldif", "31-sample-agencies.ldif",
            "32-sample-features.ldif", "33-sample-oauth-public.ldif", "34-sample-oauth-confidential.ldif",
            "36-insure-directory.ldif", "37-oauth-insure-directory.ldif", "38-sync-feature.ldif");

    private static final Map<String, String> PLACEHOLDERS = Map.of(
            "__DEMO_CLIENT_PW_HASH__", DEMO_APP_SECRET,
            "__DIRECTORY_CLIENT_PW_HASH__", DIRECTORY_CLIENT_SECRET);

    private static final String PWD_RESET = "pwdReset";
    private static final String PWD_CHANGED_TIME = "pwdChangedTime";
    private static final String[] PPOLICY_ATTRS = {
        "( 1.3.6.1.4.1.42.2.27.8.1.17 NAME 'pwdAccountLockedTime' EQUALITY caseIgnoreMatch "
            + "SYNTAX 1.3.6.1.4.1.1466.115.121.1.15 SINGLE-VALUE USAGE directoryOperation )",
        "( 1.3.6.1.4.1.42.2.27.8.1.19 NAME 'pwdFailureTime' EQUALITY caseIgnoreMatch "
            + "SYNTAX 1.3.6.1.4.1.1466.115.121.1.15 USAGE directoryOperation )",
        "( 1.3.6.1.4.1.42.2.27.8.1.23 NAME 'pwdPolicySubentry' EQUALITY distinguishedNameMatch "
            + "SYNTAX 1.3.6.1.4.1.1466.115.121.1.12 SINGLE-VALUE USAGE directoryOperation )",
        "( 1.3.6.1.4.1.42.2.27.8.1.22 NAME 'pwdReset' EQUALITY booleanMatch "
            + "SYNTAX 1.3.6.1.4.1.1466.115.121.1.7 SINGLE-VALUE USAGE directoryOperation )",
        "( 1.3.6.1.4.1.42.2.27.8.1.16 NAME 'pwdChangedTime' EQUALITY generalizedTimeMatch "
            + "ORDERING generalizedTimeOrderingMatch SYNTAX 1.3.6.1.4.1.1466.115.121.1.24 "
            + "SINGLE-VALUE USAGE directoryOperation )",
    };

    private final InMemoryDirectoryServer server;
    private final Map<Long, String> boundDn = new ConcurrentHashMap<>();

    /** @param samples LDIF files from openldap/ldif loaded after 20-dit.ldif, in order */
    public TestDirectory(final List<String> samples) throws Exception {
        final Path ldif = ldifDir();
        final InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig(BASE);
        config.setSchema(Schema.mergeSchemas(
                Schema.getDefaultStandardSchema(),
                configSchema(ldif.resolve("01-acmeinsurance-schema.ldif")),
                configSchema(ldif.resolve("02-acmeinsurance-sync-schema.ldif")),
                configSchema(ldif.resolve("03-acmeinsurance-auth-schema.ldif")),
                new Schema(new Entry("cn=ppolicy-test", new Attribute("attributeTypes", PPOLICY_ATTRS)))));
        config.addAdditionalBindCredentials(ADMIN_DN, ADMIN_PW);
        config.setReferentialIntegrityAttributes("acmeAccountDN", "acmeFeatureDN", "acmeUnderwritingCompanyDN");
        config.setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("default", 0));
        config.setEnforceSingleStructuralObjectClass(true);
        config.addInMemoryOperationInterceptor(new PolicyStandIn());

        server = new InMemoryDirectoryServer(config);
        final DN policies = new DN("ou=policies," + BASE);
        final List<String> files = new ArrayList<>();
        files.add("20-dit.ldif");
        files.addAll(samples);
        for (final String file : files) {
            try (LDIFReader r = new LDIFReader(ldif.resolve(file).toFile())) {
                Entry e;
                while ((e = r.readEntry()) != null) {
                    if (e.getParsedDN().isDescendantOf(policies, false)) {
                        continue;
                    }
                    final String password = e.getAttributeValue("userPassword");
                    final String secret = password == null ? null : PLACEHOLDERS.get(password);
                    if (secret != null) {
                        e.setAttribute("userPassword", secret);
                    }
                    server.add(e);
                }
            }
        }
        server.startListening();
    }

    public String url() {
        return "ldap://localhost:" + server.getListenPort();
    }

    /** A new connection pool bound as the directory manager; the caller closes it. */
    public LDAPConnectionPool pool() throws Exception {
        return LdapConnections.pool(url(), ADMIN_DN, ADMIN_PW, 2, null);
    }

    public InMemoryDirectoryServer server() {
        return server;
    }

    /** Reads an entry with every user and operational attribute, the ppolicy ones included. */
    public SearchResultEntry get(final String dn) throws LDAPException {
        return server.getEntry(dn, "*", "+", "pwdAccountLockedTime", "pwdFailureTime", "pwdPolicySubentry",
                PWD_RESET, PWD_CHANGED_TIME);
    }

    public void add(final String... ldifLines) throws Exception {
        server.add(ldifLines);
    }

    public void modify(final String... ldifLines) throws Exception {
        server.modify(ldifLines);
    }

    @Override
    public void close() {
        server.shutDown(true);
    }

    /** The bits of OpenLDAP's ppolicy the token service depends on. */
    private final class PolicyStandIn extends InMemoryOperationInterceptor {

        @Override
        public void processSimpleBindResult(final InMemoryInterceptedSimpleBindResult r) {
            final BindResult result = r.getResult();
            if (result.getResultCode() != ResultCode.SUCCESS) {
                return;
            }
            final String dn = r.getRequest().getBindDN();
            boundDn.put(r.getConnectionID(), dn);
            try {
                final SearchResultEntry e = dn.isEmpty() ? null : server.getEntry(dn, PWD_RESET);
                if (e != null && "TRUE".equalsIgnoreCase(e.getAttributeValue(PWD_RESET))) {
                    r.setResult(new BindResult(result.getMessageID(), ResultCode.SUCCESS, null, null, null,
                            new Control[] {new DraftBeheraLDAPPasswordPolicy10ResponseControl(null, -1,
                                    DraftBeheraLDAPPasswordPolicy10ErrorType.CHANGE_AFTER_RESET)}));
                }
            } catch (final Exception ex) {
                throw new IllegalStateException(ex);
            }
        }

        @Override
        public void processExtendedResult(final InMemoryInterceptedExtendedResult r) {
            if (!PasswordModifyExtendedRequest.PASSWORD_MODIFY_REQUEST_OID.equals(r.getRequest().getOID())
                    || r.getResult().getResultCode() != ResultCode.SUCCESS) {
                return;
            }
            final String dn = boundDn.get(r.getConnectionID());
            if (dn == null) {
                return;
            }
            try {
                if (server.getEntry(dn, PWD_RESET).hasAttribute(PWD_RESET)) {
                    server.modify(dn, new Modification(ModificationType.DELETE, PWD_RESET));
                }
                server.modify(dn, new Modification(ModificationType.REPLACE, PWD_CHANGED_TIME,
                        StaticUtils.encodeGeneralizedTime(System.currentTimeMillis())));
            } catch (final Exception ex) {
                throw new IllegalStateException(ex);
            }
        }
    }

    private static Schema configSchema(final Path file) throws Exception {
        try (LDIFReader r = new LDIFReader(file.toFile())) {
            final Entry e = r.readEntry();
            final List<Attribute> attrs = new ArrayList<>();
            final String[] types = e.getAttributeValues("olcAttributeTypes");
            final String[] classes = e.getAttributeValues("olcObjectClasses");
            if (types != null) {
                attrs.add(new Attribute("attributeTypes", types));
            }
            if (classes != null) {
                attrs.add(new Attribute("objectClasses", classes));
            }
            return new Schema(new Entry("cn=" + file.getFileName(), attrs));
        }
    }

    /** openldap/ldif, relative to backend/ (Gradle runs the tests from there). */
    private static Path ldifDir() {
        final Path dir = Path.of(System.getProperty("user.dir")).resolve("../openldap/ldif").normalize();
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException("Schema LDIFs not found at " + dir
                    + "; run the tests from backend/ in the full project checkout");
        }
        return dir;
    }
}
