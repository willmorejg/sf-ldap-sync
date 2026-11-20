package com.acmeinsurance.auth.testsupport;

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

/**
 * An UnboundID in-memory directory loaded from openldap/ldif: the three Acme Insurance
 * schemas, the tree (without ou=policies), the sample clients, agencies,
 * features and OAuth clients. Referential integrity covers acmeAccountDN and
 * acmeFeatureDN, as the refint overlay does in production.
 *
 * <p>UnboundID has no password policy, so a small interceptor stands in for the
 * parts the auth service relies on: a bind as a user with pwdReset=TRUE gets the
 * changeAfterReset response control, and a password modify clears pwdReset and
 * sets pwdChangedTime.
 */
public final class TestDirectory implements AutoCloseable {

    public static final String BASE = "dc=acmeinsurance,dc=com";
    public static final String AGENTS = "ou=agents,ou=people," + BASE;
    public static final String MEMBERS = "ou=members,ou=people," + BASE;
    public static final String INTERNAL = "ou=internal,ou=people," + BASE;
    public static final String OTHER = "ou=other,ou=people," + BASE;
    public static final String AGENCIES = "ou=agencies,ou=accounts," + BASE;
    public static final String CLIENT_ACCOUNTS = "ou=clients,ou=accounts," + BASE;
    public static final String FEATURES = "ou=features," + BASE;
    public static final String ADMIN_DN = "cn=Directory Manager";
    public static final String ADMIN_PW = "test-only-admin";
    /** Secret of the sample confidential client demo-app. */
    public static final String DEMO_APP_SECRET = "test-only-demo-app-secret-0123456789";

    private static final String[] PPOLICY_ATTRS = {
        "( 1.3.6.1.4.1.42.2.27.8.1.17 NAME 'pwdAccountLockedTime' EQUALITY caseIgnoreMatch "
            + "SYNTAX 1.3.6.1.4.1.1466.115.121.1.15 SINGLE-VALUE USAGE directoryOperation )",
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

    public TestDirectory() throws Exception {
        final Path ldif = ldifDir();
        final InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig(BASE);
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
        config.addInMemoryOperationInterceptor(new PolicyStandIn());

        server = new InMemoryDirectoryServer(config);
        final DN policies = new DN("ou=policies," + BASE);
        for (final String file : List.of("20-dit.ldif", "30-sample-clients.ldif", "31-sample-agencies.ldif",
                "32-sample-features.ldif", "33-sample-oauth-public.ldif", "34-sample-oauth-confidential.ldif")) {
            try (LDIFReader r = new LDIFReader(ldif.resolve(file).toFile())) {
                Entry e;
                while ((e = r.readEntry()) != null) {
                    if (e.getParsedDN().isDescendantOf(policies, false)) {
                        continue;
                    }
                    if ("__DEMO_CLIENT_PW_HASH__".equals(e.getAttributeValue("userPassword"))) {
                        e.setAttribute("userPassword", DEMO_APP_SECRET);
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

    public InMemoryDirectoryServer server() {
        return server;
    }

    public SearchResultEntry get(final String dn) throws Exception {
        return server.getEntry(dn, "*", "+", "pwdReset", "pwdChangedTime");
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

    /** The bits of OpenLDAP's ppolicy the auth service depends on. */
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
                final SearchResultEntry e = dn.isEmpty() ? null : server.getEntry(dn, "pwdReset");
                if (e != null && "TRUE".equalsIgnoreCase(e.getAttributeValue("pwdReset"))) {
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
            try {
                if (dn != null && server.getEntry(dn, "pwdReset").hasAttribute("pwdReset")) {
                    server.modify(dn, new Modification(ModificationType.DELETE, "pwdReset"));
                }
                if (dn != null) {
                    server.modify(dn, new Modification(ModificationType.REPLACE, "pwdChangedTime",
                            StaticUtils.encodeGeneralizedTime(System.currentTimeMillis())));
                }
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

    private static Path ldifDir() {
        final Path dir = Path.of(System.getProperty("user.dir")).resolve("../openldap/ldif").normalize();
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException("Schema LDIFs not found at " + dir
                    + "; run the tests from the auth module of the full project checkout");
        }
        return dir;
    }
}
