package com.acmeinsurance.ldapsync.salesforce;

import static com.acmeinsurance.ldapsync.core.Constants.ACME_DEACTIVATED;
import static com.acmeinsurance.ldapsync.core.Constants.ACME_SALESFORCE_ID;
import static com.acmeinsurance.ldapsync.core.Constants.ACME_SOURCE;
import static com.acmeinsurance.ldapsync.core.Constants.CN;
import static com.acmeinsurance.ldapsync.core.Constants.DISPLAY_NAME;
import static com.acmeinsurance.ldapsync.core.Constants.OBJECT_CLASS;
import static com.acmeinsurance.ldapsync.core.Constants.OC_ACME_ACCOUNT_ACCESS;
import static com.acmeinsurance.ldapsync.core.Constants.OC_ACME_FEATURE_ACCESS;
import static com.acmeinsurance.ldapsync.core.Constants.OC_ACME_SYNC_MANAGED;
import static com.acmeinsurance.ldapsync.core.Constants.OC_ACME_USER;
import static com.acmeinsurance.ldapsync.core.Constants.OC_INET_ORG_PERSON;
import static com.acmeinsurance.ldapsync.core.Constants.SOURCE_SALESFORCE;
import static com.acmeinsurance.ldapsync.core.Constants.USER_TYPE_AGENT;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import org.jboss.logging.Logger;

import com.acmeinsurance.ldapsync.SyncConfig;
import com.acmeinsurance.ldapsync.core.AccountLevel;
import com.acmeinsurance.ldapsync.core.AccountMapper;
import com.acmeinsurance.ldapsync.core.AccountTreePlanner;
import com.acmeinsurance.ldapsync.core.AgentMapper;
import com.acmeinsurance.ldapsync.core.PersonSpec;
import com.acmeinsurance.ldapsync.core.SafetyGuard;
import com.acmeinsurance.ldapsync.core.SyncReport;
import com.acmeinsurance.ldapsync.ldap.LdapDirectory;
import com.acmeinsurance.ldapsync.ldap.PersonWriter;
import com.unboundid.ldap.sdk.DN;
import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;
import com.unboundid.ldap.sdk.ResultCode;
import com.unboundid.ldap.sdk.SearchResultEntry;
import com.unboundid.ldap.sdk.SearchScope;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import jakarta.inject.Provider;

/**
 * Mirrors Salesforce into OpenLDAP:
 * <ol>
 * <li>agency/agreement/book Account records become the tree under ou=agencies;</li>
 * <li>User records (brokers and agents) become entries under ou=people/ou=agents,
 *     with acmeAccountDN grants pointing into that tree.</li>
 * </ol>
 * Every run is a full, idempotent comparison, so it is safe to run as often as
 * needed; change events from Salesforce just trigger an earlier run.
 *
 * <p>Internal users, members and others are not in Salesforce and are never
 * touched here (see CsvIntakeService).
 */
@ApplicationScoped
@Named("salesforceSync")
public class SalesforceSyncService {

    private static final Logger LOG = Logger.getLogger(SalesforceSyncService.class);
    private static final String[] ACCOUNT_ATTRS = {OBJECT_CLASS, CN, DISPLAY_NAME, ACME_SALESFORCE_ID, ACME_SOURCE};

    private final SyncConfig cfg;
    private final SalesforceReader salesforce;
    private final Provider<LdapDirectory> directory;

    private final ReentrantLock running = new ReentrantLock();
    private final AtomicBoolean dirty = new AtomicBoolean();
    private final AtomicReference<Map<String, Object>> last = new AtomicReference<>(Map.of("status", "no run yet"));

    public SalesforceSyncService(final SyncConfig cfg, final SalesforceReader salesforce,
                                 final Provider<LdapDirectory> directory) {
        this.cfg = cfg;
        this.salesforce = salesforce;
        this.directory = directory;
    }

    /** Full comparison of Salesforce against the directory. Skips if a run is in progress. */
    public Map<String, Object> fullSync() {
        if (!running.tryLock()) {
            return Map.of("status", "skipped: a sync is already running");
        }
        final SyncReport r = new SyncReport(SOURCE_SALESFORCE);
        try {
            dirty.set(false);
            final LdapDirectory dir = directory.get();
            syncAccounts(dir, r);
            if (cfg.agents().enabled()) {
                syncAgents(dir, r);
            }
        } catch (final Exception e) {
            LOG.error("Salesforce sync failed", e);
            r.error("sync aborted: " + e);
        } finally {
            running.unlock();
        }
        r.finish();
        LOG.info(r);
        final Map<String, Object> result = r.toMap();
        last.set(result);
        return result;
    }

    /** Called for each Salesforce change event; the next debounce tick runs a sync. */
    public void markDirty(final Object event) {
        dirty.set(true);
    }

    /** Runs a sync if a change event arrived since the last one; otherwise returns null. */
    public Map<String, Object> runIfDirty() {
        return dirty.get() ? fullSync() : null;
    }

    public Map<String, Object> lastReport() {
        return last.get();
    }

    // ---- agencies, agreements, books ---------------------------------------

    void syncAccounts(final LdapDirectory dir, final SyncReport r) throws Exception {
        final SyncConfig.Accounts a = cfg.accounts();
        final String base = cfg.ldap().agenciesDn();

        final Map<String, AccountLevel> types = new LinkedHashMap<>();
        a.recordTypes().forEach((sfType, level) -> types.put(sfType, AccountLevel.parse(level)));
        final var mapping = new AccountMapper.Mapping(a.idField(), a.parentField(), a.levelField(), a.cnField(), a.nameField(), types);

        final List<Map<String, Object>> rows = salesforce.query(a.soql());
        r.set("accounts.sourceRows", rows.size());
        final AccountMapper.Result mapped = AccountMapper.map(rows, mapping);
        mapped.warnings().forEach(r::warn);
        r.set("accounts.skippedOtherTypes", mapped.skipped());

        final AccountTreePlanner.Plan plan = AccountTreePlanner.plan(mapped.nodes(), base);
        plan.rejected().forEach(x -> r.error("account " + x.node().sfId() + " (" + x.node().cn() + "): " + x.reason()));

        final Set<String> failed = new HashSet<>();
        for (final AccountLevel level : AccountLevel.values()) {
            // Re-read each level so DNs reflect any parent renames or moves just made.
            final Map<String, SearchResultEntry> existing = indexBySalesforceId(
                    dir.searchAll(base, SearchScope.SUB, Filter.createPresenceFilter(ACME_SALESFORCE_ID),
                            ACCOUNT_ATTRS));
            for (final AccountTreePlanner.Planned p : plan.ordered()) {
                if (p.node().level() != level) {
                    continue;
                }
                final String id = p.node().sfId();
                if (p.node().parentSfId() != null && failed.contains(p.node().parentSfId())) {
                    failed.add(id);
                    r.error("account " + id + ": skipped because its parent could not be written");
                    continue;
                }
                try {
                    if (!upsertAccount(dir, p, existing.get(id), r)) {
                        failed.add(id);
                    }
                } catch (final LDAPException e) {
                    failed.add(id);
                    r.error("account " + id + " at " + p.dn() + ": " + e.getExceptionMessage());
                }
            }
        }
        removeAccounts(dir, base, plan.seenIds(), rows.size(), r);
    }

    /** @return false if the record could not be written (its children are then skipped) */
    private boolean upsertAccount(final LdapDirectory dir, final AccountTreePlanner.Planned p,
                                  final SearchResultEntry existing,
                                  final SyncReport r) throws LDAPException {
        final var n = p.node();
        if (existing == null) {
            final SearchResultEntry atDn = dir.get(p.dn(), ACCOUNT_ATTRS);
            if (atDn != null) {
                return adoptAccount(dir, p, atDn, r);
            }
            final Entry e = new Entry(p.dn());
            e.addAttribute(OBJECT_CLASS, n.level().objectClass(), OC_ACME_SYNC_MANAGED);
            e.addAttribute(CN, n.cn());
            e.addAttribute(ACME_SALESFORCE_ID, n.sfId());
            e.addAttribute(ACME_SOURCE, SOURCE_SALESFORCE);
            if (n.displayName() != null) {
                e.addAttribute(DISPLAY_NAME, n.displayName());
            }
            dir.add(e);
            r.inc("accounts.created");
            return true;
        }

        final String owner = existing.getAttributeValue(ACME_SOURCE);
        if (!SOURCE_SALESFORCE.equalsIgnoreCase(owner)) {
            r.error("account " + n.sfId() + ": " + existing.getDN() + " is owned by '" + owner + "', not the Salesforce sync");
            return false;
        }
        if (!existing.hasObjectClass(n.level().objectClass())) {
            r.error("account " + n.sfId() + ": record type changed to " + n.level().label() + "; delete "
                    + existing.getDN() + " and the next run recreates it");
            return false;
        }
        String dn = existing.getDN();
        if (!LdapDirectory.sameDn(dn, p.dn())) {
            final DN target = new DN(p.dn());
            final boolean parentChanged = !LdapDirectory.sameDn(existing.getParentDNString(), p.parentDn());
            dir.modifyDn(dn, target.getRDNString(), parentChanged ? p.parentDn() : null);
            r.inc(parentChanged ? "accounts.moved" : "accounts.renamed");
            dn = p.dn();
        }
        final String haveName = existing.getAttributeValue(DISPLAY_NAME);
        if (n.displayName() == null ? haveName != null : !n.displayName().equals(haveName)) {
            dir.modify(dn, List.of(n.displayName() == null
                    ? new Modification(ModificationType.DELETE, DISPLAY_NAME)
                    : new Modification(ModificationType.REPLACE, DISPLAY_NAME, n.displayName())));
            r.inc("accounts.updated");
        } else {
            r.inc("accounts.unchanged");
        }
        return true;
    }

    private boolean adoptAccount(final LdapDirectory dir, final AccountTreePlanner.Planned p,
                                 final SearchResultEntry atDn,
                                 final SyncReport r) throws LDAPException {
        final var n = p.node();
        final String otherId = atDn.getAttributeValue(ACME_SALESFORCE_ID);
        if (otherId != null) {
            r.error("account " + n.sfId() + ": " + p.dn() + " already holds Salesforce record " + otherId
                    + " (two records share cn '" + n.cn() + "' under the same parent?)");
            return false;
        }
        if (!cfg.safety().adoptUnowned()) {
            r.error("account " + n.sfId() + ": " + p.dn() + " was created by hand; set "
                    + "sync.safety.adopt-unowned=true to let the sync take it over");
            return false;
        }
        if (!atDn.hasObjectClass(n.level().objectClass())) {
            r.error("account " + n.sfId() + ": " + p.dn() + " is not a " + n.level().objectClass());
            return false;
        }
        final List<Modification> mods = new ArrayList<>();
        if (!atDn.hasObjectClass(OC_ACME_SYNC_MANAGED)) {
            mods.add(new Modification(ModificationType.ADD, OBJECT_CLASS, OC_ACME_SYNC_MANAGED));
        }
        mods.add(new Modification(ModificationType.REPLACE, ACME_SALESFORCE_ID, n.sfId()));
        mods.add(new Modification(ModificationType.REPLACE, ACME_SOURCE, SOURCE_SALESFORCE));
        if (n.displayName() != null) {
            mods.add(new Modification(ModificationType.REPLACE, DISPLAY_NAME, n.displayName()));
        }
        dir.modify(p.dn(), mods);
        r.inc("accounts.adopted");
        return true;
    }

    /**
     * Deletes owned entries whose Salesforce record is gone, children first. The
     * refint overlay then removes the deleted DNs from every user's acmeAccountDN.
     */
    private void removeAccounts(final LdapDirectory dir, final String base, final Set<String> seen,
                                final int sourceRows, final SyncReport r)
            throws LDAPException {
        final List<SearchResultEntry> owned = dir.searchAll(base, SearchScope.SUB,
                Filter.createANDFilter(Filter.createEqualityFilter(ACME_SOURCE, SOURCE_SALESFORCE),
                        Filter.createPresenceFilter(ACME_SALESFORCE_ID)), ACCOUNT_ATTRS);
        final List<SearchResultEntry> gone = owned.stream()
                .filter(e -> !seen.contains(e.getAttributeValue(ACME_SALESFORCE_ID)))
                .sorted(Comparator.comparingInt((SearchResultEntry e) -> LdapDirectory.depth(e.getDN())).reversed())
                .toList();
        final var refusal = SafetyGuard.checkRemoval(sourceRows, owned.size(), gone.size(),
                cfg.safety().maxRemovePercent());
        if (refusal.isPresent()) {
            r.error("accounts not removed: " + refusal.get());
            return;
        }
        for (final SearchResultEntry e : gone) {
            try {
                dir.delete(e.getDN());
                r.inc("accounts.deleted");
            } catch (final LDAPException ex) {
                final String why = LdapDirectory.isResult(ex, ResultCode.NOT_ALLOWED_ON_NONLEAF)
                        ? "it still has child entries the sync does not own"
                        : ex.getExceptionMessage();
                r.error("account " + e.getDN() + " is gone from Salesforce but was not deleted: " + why);
            }
        }
    }

    private static Map<String, SearchResultEntry> indexBySalesforceId(final List<SearchResultEntry> entries) {
        final Map<String, SearchResultEntry> m = new HashMap<>();
        for (final SearchResultEntry e : entries) {
            m.put(e.getAttributeValue(ACME_SALESFORCE_ID), e);
        }
        return m;
    }

    // ---- brokers and agents ------------------------------------------------

    void syncAgents(final LdapDirectory dir, final SyncReport r) throws Exception {
        final SyncConfig.Agents ag = cfg.agents();
        final String agencies = cfg.ldap().agenciesDn();

        final Map<String, String> accountDns = new HashMap<>();
        for (final SearchResultEntry e : dir.searchAll(agencies, SearchScope.SUB,
                Filter.createPresenceFilter(ACME_SALESFORCE_ID), ACME_SALESFORCE_ID)) {
            accountDns.put(e.getAttributeValue(ACME_SALESFORCE_ID), e.getDN());
        }

        final List<Map<String, Object>> users = salesforce.query(ag.soql());
        r.set("agents.sourceRows", users.size());
        Map<String, Set<String>> entitlements = Map.of();
        if (ag.entitlementSoql().isPresent()) {
            entitlements = AgentMapper.indexEntitlements(salesforce.query(ag.entitlementSoql().get()),
                    ag.entitlementContactField(), ag.entitlementAccountField());
        }

        final var mapping = new AgentMapper.Mapping(ag.idField(), ag.uidField(), ag.activeField(), ag.contactField(),
                ag.agencyField().orElse(null), ag.attributes());
        final AgentMapper.Result mapped = AgentMapper.map(users, entitlements, mapping, accountDns::get);
        mapped.warnings().forEach(r::warn);

        final PersonWriter writer = new PersonWriter(dir, cfg.ldap().peopleDn());
        final var profile = new PersonWriter.Profile(SOURCE_SALESFORCE, USER_TYPE_AGENT, ag.ou(),
                // acmeFeatureAccess so special agents can be given feature grants by hand;
                // the sync itself never touches acmeFeatureDN.
                List.of(OC_INET_ORG_PERSON, OC_ACME_USER, OC_ACME_ACCOUNT_ACCESS, OC_ACME_FEATURE_ACCESS),
                ag.passwordPolicy(),
                dn -> LdapDirectory.isUnder(dn, agencies), ag.pruneGrants(), cfg.safety().adoptUnowned());

        final Set<String> seen = new HashSet<>();
        for (final PersonSpec spec : mapped.people()) {
            seen.add(spec.sfId());
            try {
                writer.upsert(spec, profile, r);
            } catch (final LDAPException e) {
                r.error("agent " + spec.uid() + " (" + spec.sfId() + "): " + e.getExceptionMessage());
            }
        }

        // Agents who are no longer returned (deactivated or filtered out in Salesforce) are locked, not deleted.
        final List<SearchResultEntry> owned = dir.searchAll(ag.ou(), SearchScope.ONE,
                Filter.createANDFilter(Filter.createEqualityFilter(ACME_SOURCE, SOURCE_SALESFORCE),
                        Filter.createPresenceFilter(ACME_SALESFORCE_ID)), PersonWriter.READ_ATTRS);
        final List<SearchResultEntry> gone = owned.stream()
                .filter(e -> !seen.contains(e.getAttributeValue(ACME_SALESFORCE_ID))
                        && !e.hasAttribute(ACME_DEACTIVATED))
                .toList();
        final var refusal = SafetyGuard.checkRemoval(users.size(), owned.size(), gone.size(), cfg.safety().maxRemovePercent());
        if (refusal.isPresent()) {
            r.error("agents not deactivated: " + refusal.get());
            return;
        }
        for (final SearchResultEntry e : gone) {
            try {
                writer.deactivate(e, profile, r);
            } catch (final LDAPException ex) {
                r.error("agent " + e.getDN() + ": " + ex.getExceptionMessage());
            }
        }
    }
}
