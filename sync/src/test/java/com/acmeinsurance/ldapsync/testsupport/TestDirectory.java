package com.acmeinsurance.ldapsync.testsupport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.acmeinsurance.ldapsync.ldap.LdapDirectory;
import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.DN;
import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.SearchResultEntry;
import com.unboundid.ldap.sdk.schema.Schema;
import com.unboundid.ldif.LDIFReader;

/**
 * An UnboundID in-memory directory set up like the real OpenLDAP server: the
 * Acme Insurance schema LDIFs from openldap/ldif, the tree from 20-dit.ldif plus the
 * sample features, and referential integrity on acmeAccountDN and acmeFeatureDN
 * (the refint overlay in production).
 *
 * <p>The cn=config schema files are converted on the fly (olcAttributeTypes to
 * attributeTypes, olcObjectClasses to objectClasses), so the tests always run
 * against the schema that ships. The password-policy entries under ou=policies
 * are skipped because UnboundID has no ppolicy schema; only the two ppolicy
 * attributes the sync writes are defined here.
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
    public static final String AGENT_POLICY = "cn=agents,ou=policies," + BASE;

    private static final String ADMIN_DN = "cn=Directory Manager";
    private static final String ADMIN_PW = "test-only-admin";

    /**
     * ppolicy attributes from OpenLDAP 2.6 (operational, so "*" does not return them).
     * pwdAccountLockedTime is GeneralizedTime in OpenLDAP, which accepts the special
     * "000001010000Z" (permanent lock). UnboundID's parser rejects year 0000, so the
     * test schema declares it as a plain string instead.
     */
    private static final String[] PPOLICY_ATTRS = {
        "( 1.3.6.1.4.1.42.2.27.8.1.17 NAME 'pwdAccountLockedTime' EQUALITY caseIgnoreMatch "
            + "SYNTAX 1.3.6.1.4.1.1466.115.121.1.15 SINGLE-VALUE USAGE directoryOperation )",
        "( 1.3.6.1.4.1.42.2.27.8.1.23 NAME 'pwdPolicySubentry' EQUALITY distinguishedNameMatch "
            + "SYNTAX 1.3.6.1.4.1.1466.115.121.1.12 SINGLE-VALUE USAGE directoryOperation )",
    };

    private final InMemoryDirectoryServer server;

    public TestDirectory() throws Exception {
        Path ldif = ldifDir();
        InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig(BASE);
        config.setSchema(Schema.mergeSchemas(
                Schema.getDefaultStandardSchema(),
                configSchema(ldif.resolve("01-acmeinsurance-schema.ldif")),
                configSchema(ldif.resolve("02-acmeinsurance-sync-schema.ldif")),
                configSchema(ldif.resolve("03-acmeinsurance-auth-schema.ldif")),
                new Schema(new Entry("cn=ppolicy-test", new Attribute("attributeTypes", PPOLICY_ATTRS)))));
        config.addAdditionalBindCredentials(ADMIN_DN, ADMIN_PW);
        config.setReferentialIntegrityAttributes("acmeAccountDN", "acmeFeatureDN");
        config.setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("default", 0));
        config.setEnforceSingleStructuralObjectClass(true);

        server = new InMemoryDirectoryServer(config);
        DN policies = new DN("ou=policies," + BASE);
        for (String file : List.of("20-dit.ldif", "32-sample-features.ldif")) {
            try (LDIFReader r = new LDIFReader(ldif.resolve(file).toFile())) {
                Entry e;
                while ((e = r.readEntry()) != null) {
                    if (!e.getParsedDN().isDescendantOf(policies, false)) {
                        server.add(e);
                    }
                }
            }
        }
        server.startListening();
    }

    /** A fresh {@link LdapDirectory} (connection pool) talking to this server over LDAP. */
    public LdapDirectory connect(boolean dryRun) throws Exception {
        return new LdapDirectory("ldap://localhost:" + server.getListenPort(), ADMIN_DN, ADMIN_PW, 2, dryRun);
    }

    public InMemoryDirectoryServer server() {
        return server;
    }

    /** Reads an entry with every user attribute plus the ppolicy operational ones. */
    public SearchResultEntry get(String dn) throws LDAPException {
        return server.getEntry(dn, "*", "pwdAccountLockedTime", "pwdPolicySubentry");
    }

    public void add(String... ldifLines) throws Exception {
        server.add(ldifLines);
    }

    @Override
    public void close() {
        server.shutDown(true);
    }

    private static Schema configSchema(Path file) throws Exception {
        try (LDIFReader r = new LDIFReader(file.toFile())) {
            Entry e = r.readEntry();
            List<Attribute> attrs = new ArrayList<>();
            String[] types = e.getAttributeValues("olcAttributeTypes");
            String[] classes = e.getAttributeValues("olcObjectClasses");
            if (types != null) {
                attrs.add(new Attribute("attributeTypes", types));
            }
            if (classes != null) {
                attrs.add(new Attribute("objectClasses", classes));
            }
            return new Schema(new Entry("cn=" + file.getFileName(), attrs));
        }
    }

    /** openldap/ldif, found relative to the sync module (Maven runs tests from there). */
    private static Path ldifDir() {
        Path dir = Path.of(System.getProperty("user.dir")).resolve("../openldap/ldif").normalize();
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException("Schema LDIFs not found at " + dir
                    + "; run the tests from the sync module of the full project checkout");
        }
        return dir;
    }
}
