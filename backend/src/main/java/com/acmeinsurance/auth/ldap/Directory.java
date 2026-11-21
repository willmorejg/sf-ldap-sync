package com.acmeinsurance.auth.ldap;

import static com.acmeinsurance.common.Constants.ACME_ACCOUNT_DN;
import static com.acmeinsurance.common.Constants.ACME_DEACTIVATED;
import static com.acmeinsurance.common.Constants.ACME_FEATURE_DN;
import static com.acmeinsurance.common.Constants.ACME_GRANT_TYPE;
import static com.acmeinsurance.common.Constants.ACME_POLICY_NUMBER;
import static com.acmeinsurance.common.Constants.ACME_REDIRECT_URI;
import static com.acmeinsurance.common.Constants.ACME_UNDERWRITING_COMPANY_DN;
import static com.acmeinsurance.common.Constants.ACME_USER_TYPE;
import static com.acmeinsurance.common.Constants.CLAIM_ACCOUNTS_AGENCIES;
import static com.acmeinsurance.common.Constants.CLAIM_ACCOUNTS_AGREEMENTS;
import static com.acmeinsurance.common.Constants.CLAIM_ACCOUNTS_BOOKS;
import static com.acmeinsurance.common.Constants.CLAIM_ACCOUNTS_CLIENTS;
import static com.acmeinsurance.common.Constants.CLAIM_ACCOUNTS_UNDERWRITING_COMPANIES;
import static com.acmeinsurance.common.Constants.CN;
import static com.acmeinsurance.common.Constants.DISPLAY_NAME;
import static com.acmeinsurance.common.Constants.ENTRY_UUID;
import static com.acmeinsurance.common.Constants.GIVEN_NAME;
import static com.acmeinsurance.common.Constants.LDAP_TRUE;
import static com.acmeinsurance.common.Constants.MAIL;
import static com.acmeinsurance.common.Constants.OBJECT_CLASS;
import static com.acmeinsurance.common.Constants.OC_ACME_AGENCY_ACCOUNT;
import static com.acmeinsurance.common.Constants.OC_ACME_AGREEMENT_ACCOUNT;
import static com.acmeinsurance.common.Constants.OC_ACME_BOOK_ACCOUNT;
import static com.acmeinsurance.common.Constants.OC_ACME_CLIENT_ACCOUNT;
import static com.acmeinsurance.common.Constants.OC_ACME_FEATURE;
import static com.acmeinsurance.common.Constants.OC_ACME_OAUTH_CLIENT;
import static com.acmeinsurance.common.Constants.OC_ACME_PERMISSION;
import static com.acmeinsurance.common.Constants.OC_ACME_USER;
import static com.acmeinsurance.common.Constants.PERMANENT_LOCK;
import static com.acmeinsurance.common.Constants.PWD_ACCOUNT_LOCKED_TIME;
import static com.acmeinsurance.common.Constants.PWD_CHANGED_TIME;
import static com.acmeinsurance.common.Constants.PWD_RESET;
import static com.acmeinsurance.common.Constants.RDN_ACCOUNTS;
import static com.acmeinsurance.common.Constants.RDN_AGENCIES;
import static com.acmeinsurance.common.Constants.RDN_CLIENTS;
import static com.acmeinsurance.common.Constants.RDN_FEATURES;
import static com.acmeinsurance.common.Constants.RDN_OAUTH_CLIENTS;
import static com.acmeinsurance.common.Constants.RDN_PEOPLE;
import static com.acmeinsurance.common.Constants.RDN_SERVICES;
import static com.acmeinsurance.common.Constants.SN;
import static com.acmeinsurance.common.Constants.UID;
import static com.acmeinsurance.common.Constants.USER_PASSWORD;

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

import org.jboss.logging.Logger;

import com.acmeinsurance.common.ldap.LdapConfig;
import com.acmeinsurance.common.ldap.LdapConnections;
import com.unboundid.ldap.sdk.Control;
import com.unboundid.ldap.sdk.DN;
import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.LDAPConnection;
import com.unboundid.ldap.sdk.LDAPConnectionPool;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.LDAPResult;
import com.unboundid.ldap.sdk.RDN;
import com.unboundid.ldap.sdk.ResultCode;
import com.unboundid.ldap.sdk.SearchRequest;
import com.unboundid.ldap.sdk.SearchResult;
import com.unboundid.ldap.sdk.SearchResultEntry;
import com.unboundid.ldap.sdk.SearchScope;
import com.unboundid.ldap.sdk.SimpleBindRequest;
import com.unboundid.ldap.sdk.experimental.DraftBeheraLDAPPasswordPolicy10ErrorType;
import com.unboundid.ldap.sdk.experimental.DraftBeheraLDAPPasswordPolicy10RequestControl;
import com.unboundid.ldap.sdk.experimental.DraftBeheraLDAPPasswordPolicy10ResponseControl;
import com.unboundid.ldap.sdk.extensions.PasswordModifyExtendedRequest;
import com.unboundid.util.StaticUtils;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Everything the auth service reads from the directory. Lookups use a pool bound
 * as svc-auth (read only). Password checks bind as the user on a separate,
 * short-lived connection, so OpenLDAP's password policy (lockout, expiry, reset)
 * is applied by the server itself.
 */
@ApplicationScoped
public class Directory {

    private static final Logger LOG = Logger.getLogger(Directory.class);

    private static final String[] USER_ATTRS = {UID, CN, GIVEN_NAME, SN, MAIL, ACME_USER_TYPE,
        ACME_ACCOUNT_DN, ACME_FEATURE_DN, ACME_POLICY_NUMBER, ACME_DEACTIVATED, ENTRY_UUID, PWD_ACCOUNT_LOCKED_TIME,
        PWD_RESET, PWD_CHANGED_TIME};

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

        public boolean allows(final String grantType) {
            return grantTypes.contains(grantType);
        }
    }

    /**
     * Granted accounts, by level. Agreement and book values are paths from the agency down.
     *
     * @param underwritingCompanies agreement path to the cn of its underwriting company, for
     *                              every agreement the grants cover (see {@link #accounts})
     */
    public record Accounts(List<String> agencies, List<String> agreements, List<String> books, List<String> clients,
                           Map<String, String> underwritingCompanies) {

        public Map<String, Object> asClaim() {
            final Map<String, Object> m = new TreeMap<>();
            m.put(CLAIM_ACCOUNTS_AGENCIES, agencies);
            m.put(CLAIM_ACCOUNTS_AGREEMENTS, agreements);
            m.put(CLAIM_ACCOUNTS_BOOKS, books);
            m.put(CLAIM_ACCOUNTS_CLIENTS, clients);
            m.put(CLAIM_ACCOUNTS_UNDERWRITING_COMPANIES, underwritingCompanies);
            return m;
        }
    }

    private final LdapConnections connections;
    private final LdapConfig ldap;
    private final DN base;
    private final DN people;
    private final DN features;
    private final DN agencies;
    private final DN clientAccounts;
    private final DN oauthClients;
    private LDAPConnectionPool pool;

    public Directory(final LdapConnections connections, final LdapConfig ldap) throws LDAPException {
        this.connections = connections;
        this.ldap = ldap;
        this.base = new DN(ldap.baseDn());
        this.people = child(RDN_PEOPLE, base);
        this.features = child(RDN_FEATURES, base);
        final DN accounts = child(RDN_ACCOUNTS, base);
        this.agencies = child(RDN_AGENCIES, accounts);
        this.clientAccounts = child(RDN_CLIENTS, accounts);
        this.oauthClients = child(RDN_OAUTH_CLIENTS, child(RDN_SERVICES, base));
    }

    /** The svc-auth pool, opened on first use so the app starts while OpenLDAP is down. */
    private synchronized LDAPConnectionPool pool() throws LDAPException {
        if (pool == null) {
            try {
                pool = connections.pool(ldap.auth());
            } catch (final GeneralSecurityException e) {
                throw new LDAPException(ResultCode.CONNECT_ERROR, "TLS setup failed: " + e.getMessage(), e);
            }
        }
        return pool;
    }

    // ---- users -------------------------------------------------------------

    /** The user with this login name (uid) anywhere under ou=people, or null. */
    public User findByUsername(final String username) throws LDAPException {
        if (username == null || username.isBlank()) {
            return null;
        }
        return findUser(Filter.createEqualityFilter(UID, username.trim()));
    }

    /** The user with this entryUUID (the token subject), or null. Survives renames and moves. */
    public User findById(final String id) throws LDAPException {
        return findUser(Filter.createEqualityFilter(ENTRY_UUID, id));
    }

    private User findUser(final Filter f) throws LDAPException {
        final Filter filter = Filter.createANDFilter(f, Filter.createEqualityFilter(OBJECT_CLASS, OC_ACME_USER));
        final SearchResult r = pool().search(people.toString(), SearchScope.SUB, filter, USER_ATTRS);
        if (r.getEntryCount() != 1) {
            return null; // none, or (impossible with the unique overlay) ambiguous
        }
        return toUser(r.getSearchEntries().get(0));
    }

    private static User toUser(final SearchResultEntry e) {
        final String changed = e.getAttributeValue(PWD_CHANGED_TIME);
        Instant changedAt = null;
        if (changed != null) {
            try {
                changedAt = StaticUtils.decodeGeneralizedTime(changed).toInstant();
            } catch (final ParseException ex) {
                LOG.warnf("%s: unreadable pwdChangedTime %s", e.getDN(), changed);
            }
        }
        return new User(e.getDN(), e.getAttributeValue(ENTRY_UUID), e.getAttributeValue(UID),
                e.getAttributeValue(ACME_USER_TYPE), e.getAttributeValue(CN), e.getAttributeValue(GIVEN_NAME),
                e.getAttributeValue(SN), e.getAttributeValue(MAIL), values(e, ACME_ACCOUNT_DN),
                values(e, ACME_FEATURE_DN), sorted(values(e, ACME_POLICY_NUMBER)), e.hasAttribute(ACME_DEACTIVATED),
                e.getAttributeValue(PWD_ACCOUNT_LOCKED_TIME),
                LDAP_TRUE.equalsIgnoreCase(e.getAttributeValue(PWD_RESET)), changedAt);
    }

    /** Checks a user's password by binding as them, asking for the password policy response. */
    public BindOutcome authenticate(final User user, final String password) {
        if (password == null || password.isEmpty()) {
            return BindOutcome.INVALID; // an empty password would be an unauthenticated bind
        }
        try (LDAPConnection c = connect()) {
            LDAPResult r;
            try {
                r = c.bind(new SimpleBindRequest(user.dn(), password,
                        new DraftBeheraLDAPPasswordPolicy10RequestControl()));
            } catch (final LDAPException e) {
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
        } catch (final LDAPException e) {
            LOG.errorf("Cannot reach the directory to authenticate %s: %s", user.dn(), e.getMessage());
            throw new IllegalStateException("directory unavailable", e);
        }
    }

    /**
     * The user changes their own password (after an administrator reset). Binds as
     * the user and sends a password modify request, so the password policy checks
     * the new password and clears pwdReset.
     */
    public ChangeOutcome changePassword(final User user, final String oldPassword, final String newPassword) {
        if (oldPassword == null || oldPassword.isEmpty()) {
            return new ChangeOutcome(false, "Invalid username or password.");
        }
        try (LDAPConnection c = connect()) {
            try {
                c.bind(new SimpleBindRequest(user.dn(), oldPassword));
            } catch (final LDAPException e) {
                return new ChangeOutcome(false, "Invalid username or password.");
            }
            final LDAPResult r = c.processExtendedOperation(new PasswordModifyExtendedRequest(null, oldPassword,
                    newPassword, new Control[] {new DraftBeheraLDAPPasswordPolicy10RequestControl()}));
            if (r.getResultCode() == ResultCode.SUCCESS) {
                return new ChangeOutcome(true, null);
            }
            final DraftBeheraLDAPPasswordPolicy10ErrorType err = policyError(r);
            return new ChangeOutcome(false, err == null ? "The new password was not accepted." : switch (err) {
                case PASSWORD_TOO_SHORT -> "The new password is too short.";
                case PASSWORD_IN_HISTORY -> "The new password was used recently; choose another.";
                case INSUFFICIENT_PASSWORD_QUALITY -> "The new password is not acceptable.";
                case PASSWORD_TOO_YOUNG -> "The password was changed too recently.";
                default -> "The new password was not accepted.";
            });
        } catch (final LDAPException e) {
            LOG.errorf("Password change for %s failed: %s", user.dn(), e.getMessage());
            return new ChangeOutcome(false, "The password could not be changed.");
        }
    }

    private static DraftBeheraLDAPPasswordPolicy10ErrorType policyError(final LDAPResult r) {
        try {
            final DraftBeheraLDAPPasswordPolicy10ResponseControl c =
                    DraftBeheraLDAPPasswordPolicy10ResponseControl.get(r);
            return c == null ? null : c.getErrorType();
        } catch (final LDAPException e) {
            return null;
        }
    }

    // ---- grants ------------------------------------------------------------

    /**
     * Features and permissions from acmeFeatureDN. A feature grant expands to every
     * permission under it; a permission grant to that permission. Grants that no
     * longer resolve are ignored (refint normally removes them).
     */
    public Map<String, List<String>> features(final User user) throws LDAPException {
        final Map<String, Set<String>> out = new TreeMap<>();
        for (final String value : user.featureDns()) {
            final DN dn = parse(value);
            if (dn == null || !dn.isDescendantOf(features, false)) {
                continue;
            }
            final SearchResultEntry e = pool().getEntry(dn.toString(), OBJECT_CLASS, CN);
            if (e == null) {
                continue;
            }
            if (e.hasObjectClass(OC_ACME_FEATURE)) {
                final Set<String> perms = out.computeIfAbsent(e.getAttributeValue(CN), k -> new TreeSet<>());
                final SearchResult children = pool().search(dn.toString(), SearchScope.ONE,
                        Filter.createEqualityFilter(OBJECT_CLASS, OC_ACME_PERMISSION), CN);
                children.getSearchEntries().forEach(p -> perms.add(p.getAttributeValue(CN)));
            } else if (e.hasObjectClass(OC_ACME_PERMISSION)) {
                final String feature = rdnValue(dn.getParent());
                if (feature != null) {
                    out.computeIfAbsent(feature, k -> new TreeSet<>()).add(e.getAttributeValue(CN));
                }
            }
        }
        final Map<String, List<String>> result = new TreeMap<>();
        out.forEach((k, v) -> result.put(k, List.copyOf(v)));
        return result;
    }

    /**
     * Account grants exactly as held (not expanded), classified by object class.
     * Agreements and books are given as paths ("AG0042/AGR-0042-01/BK-000311"),
     * so a resource server can tell whether a requested book falls under a grant.
     *
     * <p>Underwriting companies are listed for every agreement the grants cover: a
     * held agreement, the agreement above a held book, and each agreement under a
     * held agency. Agreements without a company are left out.
     */
    public Accounts accounts(final User user) throws LDAPException {
        final Set<String> ag = new TreeSet<>();
        final Set<String> agr = new TreeSet<>();
        final Set<String> bk = new TreeSet<>();
        final Set<String> cl = new TreeSet<>();
        final Map<String, String> uw = new TreeMap<>();
        for (final String value : user.accountDns()) {
            final DN dn = parse(value);
            if (dn == null) {
                continue;
            }
            final SearchResultEntry e = pool().getEntry(dn.toString(), OBJECT_CLASS, ACME_UNDERWRITING_COMPANY_DN);
            if (e == null) {
                continue;
            }
            if (dn.isDescendantOf(agencies, false)) {
                final String path = path(dn, agencies);
                if (e.hasObjectClass(OC_ACME_AGENCY_ACCOUNT)) {
                    ag.add(path);
                    agreementsUnder(dn, uw);
                } else if (e.hasObjectClass(OC_ACME_AGREEMENT_ACCOUNT)) {
                    agr.add(path);
                    underwritingCompany(e, uw);
                } else if (e.hasObjectClass(OC_ACME_BOOK_ACCOUNT)) {
                    bk.add(path);
                    final SearchResultEntry parent = pool().getEntry(dn.getParentString(), OBJECT_CLASS,
                            ACME_UNDERWRITING_COMPANY_DN);
                    if (parent != null && parent.hasObjectClass(OC_ACME_AGREEMENT_ACCOUNT)) {
                        underwritingCompany(parent, uw);
                    }
                }
            } else if (dn.isDescendantOf(clientAccounts, false) && e.hasObjectClass(OC_ACME_CLIENT_ACCOUNT)) {
                cl.add(rdnValue(dn));
            }
        }
        return new Accounts(List.copyOf(ag), List.copyOf(agr), List.copyOf(bk), List.copyOf(cl), Map.copyOf(uw));
    }

    /** Adds the underwriting company of every agreement directly under an agency. */
    private void agreementsUnder(final DN agency, final Map<String, String> uw) throws LDAPException {
        final Filter f = Filter.createANDFilter(Filter.createEqualityFilter(OBJECT_CLASS, OC_ACME_AGREEMENT_ACCOUNT),
                Filter.createPresenceFilter(ACME_UNDERWRITING_COMPANY_DN));
        for (final SearchResultEntry e : pool().search(agency.toString(), SearchScope.ONE, f,
                ACME_UNDERWRITING_COMPANY_DN).getSearchEntries()) {
            underwritingCompany(e, uw);
        }
    }

    /** Adds an agreement's underwriting company (the cn of its DN), if it has one. */
    private void underwritingCompany(final SearchResultEntry agreement, final Map<String, String> uw) {
        final String value = agreement.getAttributeValue(ACME_UNDERWRITING_COMPANY_DN);
        final String name = value == null ? null : rdnValue(parse(value));
        final DN dn = parse(agreement.getDN());
        if (name != null && dn != null) {
            uw.put(path(dn, agencies), name);
        }
    }

    /** cn values from just below {@code top} down to {@code dn}, joined with "/". */
    private static String path(final DN dn, final DN top) {
        final StringBuilder sb = new StringBuilder();
        for (DN d = dn; d != null && !d.equals(top); d = d.getParent()) {
            sb.insert(0, sb.isEmpty() ? rdnValue(d) : rdnValue(d) + "/");
        }
        return sb.toString();
    }

    // ---- OAuth clients -----------------------------------------------------

    public Client findClient(final String clientId) throws LDAPException {
        if (clientId == null || clientId.isBlank()) {
            return null;
        }
        final String dn = new DN(new RDN(CN, clientId), oauthClients).toString();
        final SearchResultEntry e = pool().getEntry(dn, OBJECT_CLASS, CN, DISPLAY_NAME, ACME_REDIRECT_URI,
                ACME_GRANT_TYPE);
        if (e == null || !e.hasObjectClass(OC_ACME_OAUTH_CLIENT)) {
            return null;
        }
        final Set<String> grants = new TreeSet<>();
        values(e, ACME_GRANT_TYPE).forEach(g -> grants.add(g.toLowerCase(Locale.ROOT)));
        final String name = e.getAttributeValue(DISPLAY_NAME);
        return new Client(e.getDN(), e.getAttributeValue(CN), name == null ? clientId : name,
                hasSecret(dn), Set.copyOf(values(e, ACME_REDIRECT_URI)), Set.copyOf(grants));
    }

    /**
     * Whether the client has a secret (is confidential). svc-auth may not read
     * userPassword, only use it in a filter ("search" access, granted for
     * ou=oauth-clients alone in 10-config-db.ldif).
     */
    private boolean hasSecret(final String dn) throws LDAPException {
        final SearchResult r = pool().search(dn, SearchScope.BASE, Filter.createPresenceFilter(USER_PASSWORD),
                SearchRequest.NO_ATTRIBUTES);
        return r.getEntryCount() == 1;
    }

    /** Checks a confidential client's secret by binding as the client entry. */
    public boolean authenticateClient(final Client client, final String secret) {
        if (secret == null || secret.isEmpty()) {
            return false;
        }
        try (LDAPConnection c = connect()) {
            c.bind(client.dn(), secret);
            return true;
        } catch (final LDAPException e) {
            return false;
        }
    }

    // ---- helpers -----------------------------------------------------------

    private LDAPConnection connect() throws LDAPException {
        try {
            return connections.connect();
        } catch (final GeneralSecurityException e) {
            throw new LDAPException(ResultCode.CONNECT_ERROR, "TLS setup failed: " + e.getMessage(), e);
        }
    }

    private static DN child(final String rdn, final DN parent) throws LDAPException {
        return new DN(new RDN(rdn.substring(0, rdn.indexOf('=')), rdn.substring(rdn.indexOf('=') + 1)), parent);
    }

    private static DN parse(final String dn) {
        try {
            return new DN(dn);
        } catch (final LDAPException e) {
            return null;
        }
    }

    private static String rdnValue(final DN dn) {
        if (dn == null || dn.getRDN() == null) {
            return null;
        }
        final String[] v = dn.getRDN().getAttributeValues();
        return v.length == 0 ? null : v[0];
    }

    private static List<String> sorted(final List<String> v) {
        return List.copyOf(new TreeSet<>(v));
    }

    private static List<String> values(final SearchResultEntry e, final String name) {
        final String[] v = e.getAttributeValues(name);
        return v == null ? List.of() : Arrays.asList(v);
    }
}
