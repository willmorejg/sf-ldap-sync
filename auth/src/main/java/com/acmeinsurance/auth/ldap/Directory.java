package com.acmeinsurance.auth.ldap;

import java.security.GeneralSecurityException;
import java.text.ParseException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import javax.net.SocketFactory;

import org.jboss.logging.Logger;

import com.acmeinsurance.auth.AuthConfig;
import com.unboundid.ldap.sdk.Control;
import com.unboundid.ldap.sdk.DN;
import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.LDAPConnection;
import com.unboundid.ldap.sdk.LDAPConnectionOptions;
import com.unboundid.ldap.sdk.LDAPConnectionPool;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.LDAPResult;
import com.unboundid.ldap.sdk.LDAPURL;
import com.unboundid.ldap.sdk.RDN;
import com.unboundid.ldap.sdk.ResultCode;
import com.unboundid.ldap.sdk.SearchResult;
import com.unboundid.ldap.sdk.SearchResultEntry;
import com.unboundid.ldap.sdk.SearchScope;
import com.unboundid.ldap.sdk.SimpleBindRequest;
import com.unboundid.ldap.sdk.experimental.DraftBeheraLDAPPasswordPolicy10ErrorType;
import com.unboundid.ldap.sdk.experimental.DraftBeheraLDAPPasswordPolicy10RequestControl;
import com.unboundid.ldap.sdk.experimental.DraftBeheraLDAPPasswordPolicy10ResponseControl;
import com.unboundid.ldap.sdk.extensions.PasswordModifyExtendedRequest;
import com.unboundid.util.StaticUtils;
import com.unboundid.util.ssl.HostNameSSLSocketVerifier;
import com.unboundid.util.ssl.JVMDefaultTrustManager;
import com.unboundid.util.ssl.SSLUtil;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * Everything the auth service reads from the directory. Lookups use a pool bound
 * as svc-auth (read only). Password checks bind as the user on a separate,
 * short-lived connection, so OpenLDAP's password policy (lockout, expiry, reset)
 * is applied by the server itself.
 */
@ApplicationScoped
public class Directory {

    private static final Logger LOG = Logger.getLogger(Directory.class);

    /** ppolicy value meaning "locked until an administrator unlocks" (the sync's deactivation). */
    public static final String PERMANENT_LOCK = "000001010000Z";

    private static final String[] USER_ATTRS = {"uid", "cn", "givenName", "sn", "mail", "acmeUserType",
        "acmeAccountDN", "acmeFeatureDN", "acmePolicyNumber", "acmeDeactivated", "entryUUID", "pwdAccountLockedTime", "pwdReset",
        "pwdChangedTime"};

    /** Result of checking a user's password. */
    public enum BindOutcome {
        OK,
        /** Wrong username or password, or locked; deliberately not told apart. */
        INVALID,
        /** Correct password, but it was set by an administrator and must be changed first. */
        MUST_CHANGE,
        /** Correct password, but it has expired; an administrator must reset it. */
        EXPIRED
    }

    /** Result of a user changing their own password. */
    public record ChangeOutcome(boolean ok, String message) {
    }

    public record User(String dn, String id, String uid, String userType, String name, String givenName,
                       String familyName, String email, List<String> accountDns, List<String> featureDns,
                       List<String> policyNumbers, boolean deactivated, String lockedTime, boolean passwordReset, Instant passwordChanged) {

        /** Locked by the sync, by an administrator, or (until the next good bind) by ppolicy lockout. */
        public boolean disabled() {
            return deactivated || lockedTime != null;
        }

        /**
         * Deactivated or permanently locked: no point checking the password. A timed
         * ppolicy lockout is left to the server, which clears it on the first good
         * bind after it has run out.
         */
        public boolean blocked() {
            return deactivated || PERMANENT_LOCK.equals(lockedTime);
        }
    }

    public record Client(String dn, String clientId, String displayName, boolean confidential,
                         Set<String> redirectUris, Set<String> grantTypes) {

        public boolean allows(String grantType) {
            return grantTypes.contains(grantType);
        }
    }

    /** Granted accounts, by level. Agreement and book values are paths from the agency down. */
    public record Accounts(List<String> agencies, List<String> agreements, List<String> books, List<String> clients) {

        public Map<String, List<String>> asClaim() {
            Map<String, List<String>> m = new TreeMap<>();
            m.put("agencies", agencies);
            m.put("agreements", agreements);
            m.put("books", books);
            m.put("clients", clients);
            return m;
        }
    }

    private final AuthConfig cfg;
    private final DN base;
    private final DN people;
    private final DN features;
    private final DN agencies;
    private final DN clientAccounts;
    private final DN oauthClients;
    private LDAPConnectionPool pool;
    private SocketFactory socketFactory;
    private LDAPConnectionOptions options;
    private LDAPURL url;

    public Directory(AuthConfig cfg) throws LDAPException {
        this.cfg = cfg;
        this.base = new DN(cfg.ldap().baseDn());
        this.people = child("ou=people", base);
        this.features = child("ou=features", base);
        DN accounts = child("ou=accounts", base);
        this.agencies = child("ou=agencies", accounts);
        this.clientAccounts = child("ou=clients", accounts);
        this.oauthClients = child("ou=oauth-clients", child("ou=services", base));
    }

    void start(@Observes StartupEvent ev) throws LDAPException, GeneralSecurityException {
        url = new LDAPURL(cfg.ldap().url());
        options = new LDAPConnectionOptions();
        options.setConnectTimeoutMillis(10_000);
        options.setResponseTimeoutMillis(15_000);
        options.setSSLSocketVerifier(new HostNameSSLSocketVerifier(true));
        socketFactory = "ldaps".equalsIgnoreCase(url.getScheme())
                ? new SSLUtil(JVMDefaultTrustManager.getInstance()).createSSLSocketFactory()
                : SocketFactory.getDefault();
        LDAPConnection first = connect();
        first.bind(cfg.ldap().bindDn(), cfg.ldap().bindPassword());
        pool = new LDAPConnectionPool(first, 1, Math.max(1, cfg.ldap().poolSize()));
        pool.setRetryFailedOperationsDueToInvalidConnections(true);
        LOG.infof("Directory: %s as %s", cfg.ldap().url(), cfg.ldap().bindDn());
    }

    void stop(@Observes ShutdownEvent ev) {
        if (pool != null) {
            pool.close();
        }
    }

    // ---- users -------------------------------------------------------------

    /** The user with this login name (uid) anywhere under ou=people, or null. */
    public User findByUsername(String username) throws LDAPException {
        if (username == null || username.isBlank()) {
            return null;
        }
        return findUser(Filter.createEqualityFilter("uid", username.trim()));
    }

    /** The user with this entryUUID (the token subject), or null. Survives renames and moves. */
    public User findById(String id) throws LDAPException {
        return findUser(Filter.createEqualityFilter("entryUUID", id));
    }

    private User findUser(Filter f) throws LDAPException {
        Filter filter = Filter.createANDFilter(f, Filter.createEqualityFilter("objectClass", "acmeUser"));
        SearchResult r = pool.search(people.toString(), SearchScope.SUB, filter, USER_ATTRS);
        if (r.getEntryCount() != 1) {
            return null; // none, or (impossible with the unique overlay) ambiguous
        }
        return toUser(r.getSearchEntries().get(0));
    }

    private static User toUser(SearchResultEntry e) {
        String changed = e.getAttributeValue("pwdChangedTime");
        Instant changedAt = null;
        if (changed != null) {
            try {
                changedAt = StaticUtils.decodeGeneralizedTime(changed).toInstant();
            } catch (ParseException ex) {
                LOG.warnf("%s: unreadable pwdChangedTime %s", e.getDN(), changed);
            }
        }
        return new User(e.getDN(), e.getAttributeValue("entryUUID"), e.getAttributeValue("uid"),
                e.getAttributeValue("acmeUserType"), e.getAttributeValue("cn"), e.getAttributeValue("givenName"),
                e.getAttributeValue("sn"), e.getAttributeValue("mail"), values(e, "acmeAccountDN"),
                values(e, "acmeFeatureDN"), sorted(values(e, "acmePolicyNumber")), e.hasAttribute("acmeDeactivated"), e.getAttributeValue("pwdAccountLockedTime"),
                "TRUE".equalsIgnoreCase(e.getAttributeValue("pwdReset")), changedAt);
    }

    /** Checks a user's password by binding as them, asking for the password policy response. */
    public BindOutcome authenticate(User user, String password) {
        if (password == null || password.isEmpty()) {
            return BindOutcome.INVALID; // an empty password would be an unauthenticated bind
        }
        try (LDAPConnection c = connect()) {
            LDAPResult r;
            try {
                r = c.bind(new SimpleBindRequest(user.dn(), password,
                        new DraftBeheraLDAPPasswordPolicy10RequestControl()));
            } catch (LDAPException e) {
                if (policyError(e.toLDAPResult()) == DraftBeheraLDAPPasswordPolicy10ErrorType.PASSWORD_EXPIRED) {
                    return BindOutcome.EXPIRED;
                }
                if (e.getResultCode() != ResultCode.INVALID_CREDENTIALS) {
                    LOG.warnf("Bind for %s failed: %s", user.dn(), e.getMessage());
                }
                return BindOutcome.INVALID;
            }
            if (policyError(r) == DraftBeheraLDAPPasswordPolicy10ErrorType.CHANGE_AFTER_RESET) {
                return BindOutcome.MUST_CHANGE;
            }
            return BindOutcome.OK;
        } catch (LDAPException e) {
            LOG.errorf("Cannot reach the directory to authenticate %s: %s", user.dn(), e.getMessage());
            throw new IllegalStateException("directory unavailable", e);
        }
    }

    /**
     * The user changes their own password (after an administrator reset). Binds as
     * the user and sends a password modify request, so the password policy checks
     * the new password and clears pwdReset.
     */
    public ChangeOutcome changePassword(User user, String oldPassword, String newPassword) {
        if (oldPassword == null || oldPassword.isEmpty()) {
            return new ChangeOutcome(false, "Invalid username or password.");
        }
        try (LDAPConnection c = connect()) {
            try {
                c.bind(new SimpleBindRequest(user.dn(), oldPassword));
            } catch (LDAPException e) {
                return new ChangeOutcome(false, "Invalid username or password.");
            }
            LDAPResult r = c.processExtendedOperation(new PasswordModifyExtendedRequest(null, oldPassword,
                    newPassword, new Control[] {new DraftBeheraLDAPPasswordPolicy10RequestControl()}));
            if (r.getResultCode() == ResultCode.SUCCESS) {
                return new ChangeOutcome(true, null);
            }
            DraftBeheraLDAPPasswordPolicy10ErrorType err = policyError(r);
            return new ChangeOutcome(false, err == null ? "The new password was not accepted." : switch (err) {
                case PASSWORD_TOO_SHORT -> "The new password is too short.";
                case PASSWORD_IN_HISTORY -> "The new password was used recently; choose another.";
                case INSUFFICIENT_PASSWORD_QUALITY -> "The new password is not acceptable.";
                case PASSWORD_TOO_YOUNG -> "The password was changed too recently.";
                default -> "The new password was not accepted.";
            });
        } catch (LDAPException e) {
            LOG.errorf("Password change for %s failed: %s", user.dn(), e.getMessage());
            return new ChangeOutcome(false, "The password could not be changed.");
        }
    }

    private static DraftBeheraLDAPPasswordPolicy10ErrorType policyError(LDAPResult r) {
        try {
            DraftBeheraLDAPPasswordPolicy10ResponseControl c = DraftBeheraLDAPPasswordPolicy10ResponseControl.get(r);
            return c == null ? null : c.getErrorType();
        } catch (LDAPException e) {
            return null;
        }
    }

    // ---- grants ------------------------------------------------------------

    /**
     * Features and permissions from acmeFeatureDN. A feature grant expands to every
     * permission under it; a permission grant to that permission. Grants that no
     * longer resolve are ignored (refint normally removes them).
     */
    public Map<String, List<String>> features(User user) throws LDAPException {
        Map<String, Set<String>> out = new TreeMap<>();
        for (String value : user.featureDns()) {
            DN dn = parse(value);
            if (dn == null || !dn.isDescendantOf(features, false)) {
                continue;
            }
            SearchResultEntry e = pool.getEntry(dn.toString(), "objectClass", "cn");
            if (e == null) {
                continue;
            }
            if (e.hasObjectClass("acmeFeature")) {
                Set<String> perms = out.computeIfAbsent(e.getAttributeValue("cn"), k -> new TreeSet<>());
                SearchResult children = pool.search(dn.toString(), SearchScope.ONE,
                        Filter.createEqualityFilter("objectClass", "acmePermission"), "cn");
                children.getSearchEntries().forEach(p -> perms.add(p.getAttributeValue("cn")));
            } else if (e.hasObjectClass("acmePermission")) {
                String feature = rdnValue(dn.getParent());
                if (feature != null) {
                    out.computeIfAbsent(feature, k -> new TreeSet<>()).add(e.getAttributeValue("cn"));
                }
            }
        }
        Map<String, List<String>> result = new TreeMap<>();
        out.forEach((k, v) -> result.put(k, List.copyOf(v)));
        return result;
    }

    /**
     * Account grants exactly as held (not expanded), classified by object class.
     * Agreements and books are given as paths ("AG0042/AGR-0042-01/BK-000311"),
     * so a resource server can tell whether a requested book falls under a grant.
     */
    public Accounts accounts(User user) throws LDAPException {
        Set<String> ag = new TreeSet<>();
        Set<String> agr = new TreeSet<>();
        Set<String> bk = new TreeSet<>();
        Set<String> cl = new TreeSet<>();
        for (String value : user.accountDns()) {
            DN dn = parse(value);
            if (dn == null) {
                continue;
            }
            SearchResultEntry e = pool.getEntry(dn.toString(), "objectClass");
            if (e == null) {
                continue;
            }
            if (dn.isDescendantOf(agencies, false)) {
                String path = path(dn, agencies);
                if (e.hasObjectClass("acmeAgencyAccount")) {
                    ag.add(path);
                } else if (e.hasObjectClass("acmeAgreementAccount")) {
                    agr.add(path);
                } else if (e.hasObjectClass("acmeBookAccount")) {
                    bk.add(path);
                }
            } else if (dn.isDescendantOf(clientAccounts, false) && e.hasObjectClass("acmeClientAccount")) {
                cl.add(rdnValue(dn));
            }
        }
        return new Accounts(List.copyOf(ag), List.copyOf(agr), List.copyOf(bk), List.copyOf(cl));
    }

    /** cn values from just below {@code top} down to {@code dn}, joined with "/". */
    private static String path(DN dn, DN top) {
        StringBuilder sb = new StringBuilder();
        for (DN d = dn; d != null && !d.equals(top); d = d.getParent()) {
            sb.insert(0, sb.isEmpty() ? rdnValue(d) : rdnValue(d) + "/");
        }
        return sb.toString();
    }

    // ---- OAuth clients -----------------------------------------------------

    public Client findClient(String clientId) throws LDAPException {
        if (clientId == null || clientId.isBlank()) {
            return null;
        }
        String dn = new DN(new RDN("cn", clientId), oauthClients).toString();
        SearchResultEntry e = pool.getEntry(dn, "objectClass", "cn", "displayName", "acmeRedirectUri", "acmeGrantType");
        if (e == null || !e.hasObjectClass("acmeOAuthClient")) {
            return null;
        }
        Set<String> grants = new TreeSet<>();
        values(e, "acmeGrantType").forEach(g -> grants.add(g.toLowerCase(Locale.ROOT)));
        String name = e.getAttributeValue("displayName");
        return new Client(e.getDN(), e.getAttributeValue("cn"), name == null ? clientId : name,
                hasSecret(dn), Set.copyOf(values(e, "acmeRedirectUri")), Set.copyOf(grants));
    }

    /**
     * Whether the client has a secret (is confidential). svc-auth may not read
     * userPassword, only use it in a filter ("search" access, granted for
     * ou=oauth-clients alone in 10-config-db.ldif).
     */
    private boolean hasSecret(String dn) throws LDAPException {
        SearchResult r = pool.search(dn, SearchScope.BASE, Filter.createPresenceFilter("userPassword"), "1.1");
        return r.getEntryCount() == 1;
    }

    /** Checks a confidential client's secret by binding as the client entry. */
    public boolean authenticateClient(Client client, String secret) {
        if (secret == null || secret.isEmpty()) {
            return false;
        }
        try (LDAPConnection c = connect()) {
            c.bind(client.dn(), secret);
            return true;
        } catch (LDAPException e) {
            return false;
        }
    }

    // ---- helpers -----------------------------------------------------------

    private LDAPConnection connect() throws LDAPException {
        return new LDAPConnection(socketFactory, options, url.getHost(), url.getPort());
    }

    private static DN child(String rdn, DN parent) throws LDAPException {
        return new DN(new RDN(rdn.substring(0, rdn.indexOf('=')), rdn.substring(rdn.indexOf('=') + 1)), parent);
    }

    private static DN parse(String dn) {
        try {
            return new DN(dn);
        } catch (LDAPException e) {
            return null;
        }
    }

    private static String rdnValue(DN dn) {
        if (dn == null || dn.getRDN() == null) {
            return null;
        }
        String[] v = dn.getRDN().getAttributeValues();
        return v.length == 0 ? null : v[0];
    }

    private static List<String> sorted(List<String> v) {
        return List.copyOf(new TreeSet<>(v));
    }

    private static List<String> values(SearchResultEntry e, String name) {
        String[] v = e.getAttributeValues(name);
        return v == null ? List.of() : Arrays.asList(v);
    }
}
