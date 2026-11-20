package com.acmeinsurance.ldapsync.ldap;

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

import com.acmeinsurance.ldapsync.core.PersonSpec;
import com.acmeinsurance.ldapsync.core.SyncReport;
import com.acmeinsurance.ldapsync.core.ValueDiff;
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
public class PersonWriter {

    /** Attributes read from existing entries; pwdAccountLockedTime is operational. */
    public static final String[] READ_ATTRS = {"*", "pwdAccountLockedTime"};
    /** ppolicy value meaning "locked until an administrator unlocks". */
    public static final String PERMANENT_LOCK = "000001010000Z";

    private static final Set<String> REQUIRED_ATTRS = Set.of("cn", "sn");
    private static final DateTimeFormatter GENERALIZED_TIME =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss'Z'").withZone(ZoneOffset.UTC);

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

    public PersonWriter(LdapDirectory dir, String peopleDn) {
        this.dir = dir;
        this.peopleDn = peopleDn;
    }

    public void upsert(PersonSpec spec, Profile p, SyncReport r) throws LDAPException {
        Located found = locate(spec, p, r);
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
    public void deactivate(SearchResultEntry e, Profile p, SyncReport r) throws LDAPException {
        if (e.hasAttribute("acmeDeactivated")) {
            return;
        }
        dir.modify(e.getDN(), lockMods());
        r.inc(key(p, "deactivated"));
    }

    private Located locate(PersonSpec spec, Profile p, SyncReport r) throws LDAPException {
        if (spec.sfId() != null) {
            SearchResultEntry bySf = dir.findOne(p.ouDn(), SearchScope.ONE,
                    Filter.createEqualityFilter("acmeSalesforceId", spec.sfId()), READ_ATTRS);
            if (bySf != null) {
                return checkOwner(bySf, spec, p, r);
            }
        }
        SearchResultEntry byUid = dir.findOne(peopleDn, SearchScope.SUB,
                Filter.createEqualityFilter("uid", spec.uid()), READ_ATTRS);
        if (byUid == null) {
            return new Located(null, true);
        }
        if (!LdapDirectory.sameDn(byUid.getParentDNString(), p.ouDn())) {
            r.error(spec.uid() + ": uid is already used by " + byUid.getDN());
            return new Located(null, false);
        }
        String otherSf = byUid.getAttributeValue("acmeSalesforceId");
        if (spec.sfId() != null && otherSf != null && !otherSf.equals(spec.sfId())) {
            r.error(spec.uid() + ": uid already belongs to Salesforce user " + otherSf + ", not " + spec.sfId());
            return new Located(null, false);
        }
        return checkOwner(byUid, spec, p, r);
    }

    /** acmeSource of entries made by hand; treated exactly like no acmeSource at all. */
    public static final String MANUAL = "manual";

    /**
     * Whether {@code source} owns an entry marked {@code owner}. The CSV feeds used
     * to write csv-internal, csv-members and csv-other; "csv" owns those too and
     * rewrites them on the next update.
     */
    static boolean owns(String source, String owner) {
        if (owner == null) {
            return false;
        }
        return source.equalsIgnoreCase(owner)
                || "csv".equalsIgnoreCase(source) && owner.toLowerCase(Locale.ROOT).startsWith("csv-");
    }

    private Located checkOwner(SearchResultEntry e, PersonSpec spec, Profile p, SyncReport r) {
        String owner = e.getAttributeValue("acmeSource");
        if (owns(p.source(), owner)) {
            return new Located(e, true);
        }
        if (MANUAL.equalsIgnoreCase(owner)) {
            owner = null; // made by hand, marked as such
        }
        if (owner == null && p.adoptUnowned()) {
            r.inc(key(p, "adopted"));
            return new Located(e, true);
        }
        r.error(spec.uid() + ": " + e.getDN() + (owner == null
                ? " was created by hand; set sync.safety.adopt-unowned=true to let the sync take it over"
                : " is owned by source '" + owner + "'"));
        return new Located(null, false);
    }

    private void create(PersonSpec spec, Profile p, SyncReport r) throws LDAPException {
        if (!spec.active()) {
            r.inc(key(p, "skippedInactive"));
            return;
        }
        String dn = new DN(new RDN("uid", spec.uid()), new DN(p.ouDn())).toString();
        Entry e = new Entry(dn);
        List<String> classes = new ArrayList<>(p.objectClasses());
        classes.add("acmeSyncManaged");
        e.addAttribute(new Attribute("objectClass", classes));
        e.addAttribute("uid", spec.uid());
        e.addAttribute("acmeUserType", p.userType());
        e.addAttribute("acmeSource", p.source());
        if (spec.sfId() != null) {
            e.addAttribute("acmeSalesforceId", spec.sfId());
        }
        spec.attributes().forEach((name, values) -> {
            if (!values.isEmpty()) {
                e.addAttribute(new Attribute(name, values));
            }
        });
        if (!e.hasAttribute("sn")) {
            e.addAttribute("sn", spec.uid());
        }
        if (!e.hasAttribute("cn")) {
            String given = e.getAttributeValue("givenName");
            String sn = e.getAttributeValue("sn");
            e.addAttribute("cn", given == null ? sn : given + " " + sn);
        }
        if (spec.accountDns() != null && !spec.accountDns().isEmpty()) {
            e.addAttribute(new Attribute("acmeAccountDN", spec.accountDns()));
        }
        if (spec.policyNumbers() != null && !spec.policyNumbers().isEmpty()) {
            e.addAttribute(new Attribute("acmePolicyNumber", spec.policyNumbers()));
        }
        if (spec.featureDns() != null && !spec.featureDns().isEmpty()) {
            e.addAttribute(new Attribute("acmeFeatureDN", spec.featureDns()));
        }
        if (p.passwordPolicyDn() != null) {
            e.addAttribute("pwdPolicySubentry", p.passwordPolicyDn());
        }
        dir.add(e);
        r.inc(key(p, "created"));
    }

    private void update(SearchResultEntry e, PersonSpec spec, Profile p, SyncReport r) throws LDAPException {
        String dn = e.getDN();
        RDN wanted = new RDN("uid", spec.uid());
        if (!new DN(dn).getRDN().equals(wanted)) {
            dir.modifyDn(dn, wanted.toString(), null);
            if (!dir.isDryRun()) {
                dn = new DN(wanted, new DN(e.getParentDNString())).toString();
            }
            r.inc(key(p, "renamed"));
        }

        List<Modification> mods = new ArrayList<>();
        List<String> classes = new ArrayList<>(p.objectClasses());
        classes.add("acmeSyncManaged");
        for (String oc : classes) {
            if (!e.hasObjectClass(oc)) {
                mods.add(new Modification(ModificationType.ADD, "objectClass", oc));
            }
        }
        replaceIfDifferent(mods, e, "acmeSource", List.of(p.source()));
        replaceIfDifferent(mods, e, "acmeUserType", List.of(p.userType()));
        if (spec.sfId() != null) {
            replaceIfDifferent(mods, e, "acmeSalesforceId", List.of(spec.sfId()));
        }
        spec.attributes().forEach((name, values) -> replaceIfDifferent(mods, e, name, values));

        if (spec.accountDns() != null) {
            addDiff(mods, "acmeAccountDN", ValueDiff.diff(values(e, "acmeAccountDN"), spec.accountDns(),
                    p.grantScope(), p.pruneGrants(), LdapDirectory::normalize));
        }
        if (spec.featureDns() != null) { // only CSV feeds send these; they own all of an entry's feature grants
            addDiff(mods, "acmeFeatureDN", ValueDiff.diff(values(e, "acmeFeatureDN"), spec.featureDns(),
                    v -> true, true, LdapDirectory::normalize));
        }
        if (spec.policyNumbers() != null) {
            addDiff(mods, "acmePolicyNumber", ValueDiff.diff(values(e, "acmePolicyNumber"), spec.policyNumbers(),
                    v -> true, true, v -> v.trim().toLowerCase(Locale.ROOT)));
        }

        boolean deactivated = e.hasAttribute("acmeDeactivated");
        if (spec.active() && deactivated) {
            mods.add(new Modification(ModificationType.DELETE, "acmeDeactivated"));
            if (PERMANENT_LOCK.equals(e.getAttributeValue("pwdAccountLockedTime"))) {
                mods.add(new Modification(ModificationType.DELETE, "pwdAccountLockedTime"));
            }
            r.inc(key(p, "reactivated"));
        } else if (!spec.active() && !deactivated) {
            mods.addAll(lockMods());
            r.inc(key(p, "deactivated"));
        }

        if (mods.isEmpty()) {
            r.inc(key(p, "unchanged"));
        } else {
            dir.modify(dn, mods);
            r.inc(key(p, "updated"));
        }
    }

    private static List<Modification> lockMods() {
        return List.of(
                new Modification(ModificationType.REPLACE, "pwdAccountLockedTime", PERMANENT_LOCK),
                new Modification(ModificationType.REPLACE, "acmeDeactivated", GENERALIZED_TIME.format(Instant.now())));
    }

    private static void replaceIfDifferent(List<Modification> mods, Entry e, String name, List<String> desired) {
        Set<String> have = new LinkedHashSet<>(values(e, name));
        Set<String> want = new LinkedHashSet<>(desired);
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

    private static void addDiff(List<Modification> mods, String name, ValueDiff.Diff d) {
        if (!d.add().isEmpty()) {
            mods.add(new Modification(ModificationType.ADD, name, d.add().toArray(String[]::new)));
        }
        if (!d.remove().isEmpty()) {
            mods.add(new Modification(ModificationType.DELETE, name, d.remove().toArray(String[]::new)));
        }
    }

    private static List<String> values(Entry e, String name) {
        String[] v = e.getAttributeValues(name);
        return v == null ? List.of() : Arrays.asList(v);
    }

    private static String key(Profile p, String what) {
        return p.userType() + "." + what;
    }
}
