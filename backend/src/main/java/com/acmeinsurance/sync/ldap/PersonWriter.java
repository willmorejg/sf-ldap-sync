package com.acmeinsurance.sync.ldap;

import static com.acmeinsurance.common.Constants.ACME_ACCOUNT_DN;
import static com.acmeinsurance.common.Constants.ACME_DEACTIVATED;
import static com.acmeinsurance.common.Constants.ACME_FEATURE_DN;
import static com.acmeinsurance.common.Constants.ACME_POLICY_NUMBER;
import static com.acmeinsurance.common.Constants.ACME_SALESFORCE_ID;
import static com.acmeinsurance.common.Constants.ACME_SOURCE;
import static com.acmeinsurance.common.Constants.ACME_USER_TYPE;
import static com.acmeinsurance.common.Constants.CN;
import static com.acmeinsurance.common.Constants.GIVEN_NAME;
import static com.acmeinsurance.common.Constants.OBJECT_CLASS;
import static com.acmeinsurance.common.Constants.OC_ACME_SYNC_MANAGED;
import static com.acmeinsurance.common.Constants.PERMANENT_LOCK;
import static com.acmeinsurance.common.Constants.PWD_ACCOUNT_LOCKED_TIME;
import static com.acmeinsurance.common.Constants.PWD_POLICY_SUBENTRY;
import static com.acmeinsurance.common.Constants.SN;
import static com.acmeinsurance.common.Constants.SOURCE_CSV;
import static com.acmeinsurance.common.Constants.SOURCE_CSV_LEGACY_PREFIX;
import static com.acmeinsurance.common.Constants.SOURCE_MANUAL;
import static com.acmeinsurance.common.Constants.UID;
import static com.acmeinsurance.sync.core.SyncReport.COUNT_ADOPTED;
import static com.acmeinsurance.sync.core.SyncReport.COUNT_CREATED;
import static com.acmeinsurance.sync.core.SyncReport.COUNT_DEACTIVATED;
import static com.acmeinsurance.sync.core.SyncReport.COUNT_REACTIVATED;
import static com.acmeinsurance.sync.core.SyncReport.COUNT_RENAMED;
import static com.acmeinsurance.sync.core.SyncReport.COUNT_SKIPPED_INACTIVE;
import static com.acmeinsurance.sync.core.SyncReport.COUNT_UNCHANGED;
import static com.acmeinsurance.sync.core.SyncReport.COUNT_UPDATED;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

import com.acmeinsurance.common.ldap.DnUtil;
import com.acmeinsurance.sync.core.PersonSpec;
import com.acmeinsurance.sync.core.SyncReport;
import com.acmeinsurance.sync.core.ValueDiff;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.DN;
import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;
import com.unboundid.ldap.sdk.RDN;
import com.unboundid.ldap.sdk.SearchResultEntry;
import com.unboundid.ldap.sdk.SearchScope;

/**
 * Creates, updates and deactivates person entries for one source (Salesforce
 * agents, or a CSV feed for internal users, members or others).
 *
 * <p>Ownership rule: a source only changes entries whose acmeSource it owns.
 * Entries made by hand have no acmeSource and are left alone unless
 * {@code adoptUnowned} is set. Passwords are never written.
 */
public final class PersonWriter {

    /** Attributes read from existing entries; pwdAccountLockedTime is operational. */
    protected static final String[] READ_ATTRS = {"*", PWD_ACCOUNT_LOCKED_TIME};

    private static final Set<String> REQUIRED_ATTRS = Set.of(CN, SN);
    /** LDAP GeneralizedTime in UTC, to the second. */
    private static final String GENERALIZED_TIME_PATTERN = "yyyyMMddHHmmss'Z'";
    private static final DateTimeFormatter GENERALIZED_TIME =
            DateTimeFormatter.ofPattern(GENERALIZED_TIME_PATTERN).withZone(ZoneOffset.UTC);

    public static String[] getReadAttrs() {
        return READ_ATTRS;
    }

    /**
     * How one source writes people.
     *
     * @param source           value stored in acmeSource: "salesforce" or "csv"
     * @param userType         acmeUserType value: internal, agent, member or other
     * @param ouDn             OU the source's entries live in
     * @param objectClasses    object classes every entry gets (acmeSyncManaged is added automatically)
     * @param passwordPolicyDn pwdPolicySubentry set on create; null means the default policy
     * @param grantScope       which acmeAccountDN values this source manages
     * @param pruneGrants      remove in-scope grants the source no longer lists
     * @param adoptUnowned     take over matching entries that have no acmeSource
     */
    public record Profile(String source, String userType, String ouDn, List<String> objectClasses,
                          String passwordPolicyDn, Predicate<String> grantScope, boolean pruneGrants,
                          boolean adoptUnowned) {
    }

    private record Located(SearchResultEntry entry, boolean ok) {
    }

    private final LdapDirectory dir;
    private final String peopleDn;

    public PersonWriter(final LdapDirectory dir, final String peopleDn) {
        this.dir = dir;
        this.peopleDn = peopleDn;
    }

    public void upsert(final PersonSpec spec, final Profile p, final SyncReport r) throws LDAPException {
        final Located found = locate(spec, p, r);
        if (!found.ok()) {
            return;
        }
        if (found.entry() == null) {
            create(spec, p, r);
        } else {
            update(found.entry(), spec, p, r);
        }
    }

    /** Locks an entry the source no longer lists (no-op if already deactivated). */
    public void deactivate(final SearchResultEntry e, final Profile p, final SyncReport r) throws LDAPException {
        if (e.hasAttribute(ACME_DEACTIVATED)) {
            return;
        }
        dir.modify(e.getDN(), lockMods());
        r.inc(key(p, COUNT_DEACTIVATED));
    }

    private Located locate(final PersonSpec spec, final Profile p, final SyncReport r) throws LDAPException {
        if (spec.sfId() != null) {
            final SearchResultEntry bySf = dir.findOne(p.ouDn(), SearchScope.ONE,
                    Filter.createEqualityFilter(ACME_SALESFORCE_ID, spec.sfId()), READ_ATTRS);
            if (bySf != null) {
                return checkOwner(bySf, spec, p, r);
            }
        }
        final SearchResultEntry byUid = dir.findOne(peopleDn, SearchScope.SUB,
                Filter.createEqualityFilter(UID, spec.uid()), READ_ATTRS);
        if (byUid == null) {
            return new Located(null, true);
        }
        if (!DnUtil.sameDn(byUid.getParentDNString(), p.ouDn())) {
            r.error(spec.uid() + ": uid is already used by " + byUid.getDN());
            return new Located(null, false);
        }
        final String otherSf = byUid.getAttributeValue(ACME_SALESFORCE_ID);
        if (spec.sfId() != null && otherSf != null && !otherSf.equals(spec.sfId())) {
            r.error(spec.uid() + ": uid already belongs to Salesforce user " + otherSf + ", not " + spec.sfId());
            return new Located(null, false);
        }
        return checkOwner(byUid, spec, p, r);
    }

    /**
     * Whether {@code source} owns an entry marked {@code owner}. The CSV feeds used
     * to write csv-internal, csv-members and csv-other; "csv" owns those too and
     * rewrites them on the next update.
     */
    static boolean owns(final String source, final String owner) {
        if (owner == null) {
            return false;
        }
        return source.equalsIgnoreCase(owner)
                || SOURCE_CSV.equalsIgnoreCase(source)
                        && owner.toLowerCase(Locale.ROOT).startsWith(SOURCE_CSV_LEGACY_PREFIX);
    }

    private Located checkOwner(final SearchResultEntry e, final PersonSpec spec, final Profile p, final SyncReport r) {
        String owner = e.getAttributeValue(ACME_SOURCE);
        if (owns(p.source(), owner)) {
            return new Located(e, true);
        }
        if (SOURCE_MANUAL.equalsIgnoreCase(owner)) {
            owner = null; // made by hand, marked as such
        }
        if (owner == null && p.adoptUnowned()) {
            r.inc(key(p, COUNT_ADOPTED));
            return new Located(e, true);
        }
        r.error(spec.uid() + ": " + e.getDN() + (owner == null
                ? " was created by hand; set sync.safety.adopt-unowned=true to let the sync take it over"
                : " is owned by source '" + owner + "'"));
        return new Located(null, false);
    }

    private void create(final PersonSpec spec, final Profile p, final SyncReport r) throws LDAPException {
        if (!spec.active()) {
            r.inc(key(p, COUNT_SKIPPED_INACTIVE));
            return;
        }
        final String dn = new DN(new RDN(UID, spec.uid()), new DN(p.ouDn())).toString();
        final Entry e = new Entry(dn);
        final List<String> classes = new ArrayList<>(p.objectClasses());
        classes.add(OC_ACME_SYNC_MANAGED);
        e.addAttribute(new Attribute(OBJECT_CLASS, classes));
        e.addAttribute(UID, spec.uid());
        e.addAttribute(ACME_USER_TYPE, p.userType());
        e.addAttribute(ACME_SOURCE, p.source());
        if (spec.sfId() != null) {
            e.addAttribute(ACME_SALESFORCE_ID, spec.sfId());
        }
        spec.attributes().forEach((name, values) -> {
            if (!values.isEmpty()) {
                e.addAttribute(new Attribute(name, values));
            }
        });
        if (!e.hasAttribute(SN)) {
            e.addAttribute(SN, spec.uid());
        }
        if (!e.hasAttribute(CN)) {
            final String given = e.getAttributeValue(GIVEN_NAME);
            final String sn = e.getAttributeValue(SN);
            e.addAttribute(CN, given == null ? sn : given + " " + sn);
        }
        if (spec.accountDns() != null && !spec.accountDns().isEmpty()) {
            e.addAttribute(new Attribute(ACME_ACCOUNT_DN, spec.accountDns()));
        }
        if (spec.policyNumbers() != null && !spec.policyNumbers().isEmpty()) {
            e.addAttribute(new Attribute(ACME_POLICY_NUMBER, spec.policyNumbers()));
        }
        if (spec.featureDns() != null && !spec.featureDns().isEmpty()) {
            e.addAttribute(new Attribute(ACME_FEATURE_DN, spec.featureDns()));
        }
        if (p.passwordPolicyDn() != null) {
            e.addAttribute(PWD_POLICY_SUBENTRY, p.passwordPolicyDn());
        }
        dir.add(e);
        r.inc(key(p, COUNT_CREATED));
    }

    private void update(final SearchResultEntry e, final PersonSpec spec, final Profile p,
                        final SyncReport r) throws LDAPException {
        String dn = e.getDN();
        final RDN wanted = new RDN(UID, spec.uid());
        if (!new DN(dn).getRDN().equals(wanted)) {
            dir.modifyDn(dn, wanted.toString(), null);
            if (!dir.isDryRun()) {
                dn = new DN(wanted, new DN(e.getParentDNString())).toString();
            }
            r.inc(key(p, COUNT_RENAMED));
        }

        final List<Modification> mods = new ArrayList<>();
        final List<String> classes = new ArrayList<>(p.objectClasses());
        classes.add(OC_ACME_SYNC_MANAGED);
        for (final String oc : classes) {
            if (!e.hasObjectClass(oc)) {
                mods.add(new Modification(ModificationType.ADD, OBJECT_CLASS, oc));
            }
        }
        replaceIfDifferent(mods, e, ACME_SOURCE, List.of(p.source()));
        replaceIfDifferent(mods, e, ACME_USER_TYPE, List.of(p.userType()));
        if (spec.sfId() != null) {
            replaceIfDifferent(mods, e, ACME_SALESFORCE_ID, List.of(spec.sfId()));
        }
        spec.attributes().forEach((name, values) -> replaceIfDifferent(mods, e, name, values));

        if (spec.accountDns() != null) {
            addDiff(mods, ACME_ACCOUNT_DN, ValueDiff.diff(values(e, ACME_ACCOUNT_DN), spec.accountDns(),
                    p.grantScope(), p.pruneGrants(), DnUtil::normalize));
        }
        if (spec.featureDns() != null) { // only CSV feeds send these; they own all of an entry's feature grants
            addDiff(mods, ACME_FEATURE_DN, ValueDiff.diff(values(e, ACME_FEATURE_DN), spec.featureDns(),
                    v -> true, true, DnUtil::normalize));
        }
        if (spec.policyNumbers() != null) {
            addDiff(mods, ACME_POLICY_NUMBER, ValueDiff.diff(values(e, ACME_POLICY_NUMBER), spec.policyNumbers(),
                    v -> true, true, v -> v.trim().toLowerCase(Locale.ROOT)));
        }

        final boolean deactivated = e.hasAttribute(ACME_DEACTIVATED);
        if (spec.active() && deactivated) {
            mods.add(new Modification(ModificationType.DELETE, ACME_DEACTIVATED));
            if (PERMANENT_LOCK.equals(e.getAttributeValue(PWD_ACCOUNT_LOCKED_TIME))) {
                mods.add(new Modification(ModificationType.DELETE, PWD_ACCOUNT_LOCKED_TIME));
            }
            r.inc(key(p, COUNT_REACTIVATED));
        } else if (!spec.active() && !deactivated) {
            mods.addAll(lockMods());
            r.inc(key(p, COUNT_DEACTIVATED));
        }

        if (mods.isEmpty()) {
            r.inc(key(p, COUNT_UNCHANGED));
        } else {
            dir.modify(dn, mods);
            r.inc(key(p, COUNT_UPDATED));
        }
    }

    private static List<Modification> lockMods() {
        return List.of(
                new Modification(ModificationType.REPLACE, PWD_ACCOUNT_LOCKED_TIME, PERMANENT_LOCK),
                new Modification(ModificationType.REPLACE, ACME_DEACTIVATED, GENERALIZED_TIME.format(Instant.now())));
    }

    private static void replaceIfDifferent(final List<Modification> mods, final Entry e, final String name,
                                           final List<String> desired) {
        final Set<String> have = new LinkedHashSet<>(values(e, name));
        final Set<String> want = new LinkedHashSet<>(desired);
        if (have.equals(want)) {
            return;
        }
        if (want.isEmpty()) {
            if (!have.isEmpty() && !REQUIRED_ATTRS.contains(name)) {
                mods.add(new Modification(ModificationType.DELETE, name));
            }
            return;
        }
        mods.add(new Modification(ModificationType.REPLACE, name, want.toArray(String[]::new)));
    }

    private static void addDiff(final List<Modification> mods, final String name, final ValueDiff.Diff d) {
        if (!d.add().isEmpty()) {
            mods.add(new Modification(ModificationType.ADD, name, d.add().toArray(String[]::new)));
        }
        if (!d.remove().isEmpty()) {
            mods.add(new Modification(ModificationType.DELETE, name, d.remove().toArray(String[]::new)));
        }
    }

    private static List<String> values(final Entry e, final String name) {
        final String[] v = e.getAttributeValues(name);
        return v == null ? List.of() : Arrays.asList(v);
    }

    private static String key(final Profile p, final String outcome) {
        return SyncReport.key(p.userType(), outcome);
    }
}
