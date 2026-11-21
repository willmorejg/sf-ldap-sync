package com.acmeinsurance.directory.service;

import static com.acmeinsurance.common.Constants.ACME_DEACTIVATED;
import static com.acmeinsurance.common.Constants.ACME_SOURCE;
import static com.acmeinsurance.common.Constants.ACME_UNDERWRITING_COMPANY_DN;
import static com.acmeinsurance.common.Constants.ACME_USER_TYPE;
import static com.acmeinsurance.common.Constants.ALL_USER_ATTRIBUTES;
import static com.acmeinsurance.common.Constants.CN;
import static com.acmeinsurance.common.Constants.CREATE_TIMESTAMP;
import static com.acmeinsurance.common.Constants.DESCRIPTION;
import static com.acmeinsurance.common.Constants.DISPLAY_NAME;
import static com.acmeinsurance.common.Constants.GIVEN_NAME;
import static com.acmeinsurance.common.Constants.JSON_ATTRIBUTES;
import static com.acmeinsurance.common.Constants.JSON_CN;
import static com.acmeinsurance.common.Constants.JSON_CREATED;
import static com.acmeinsurance.common.Constants.JSON_DEACTIVATED;
import static com.acmeinsurance.common.Constants.JSON_DELETABLE;
import static com.acmeinsurance.common.Constants.JSON_DELETED;
import static com.acmeinsurance.common.Constants.JSON_DESCRIPTION;
import static com.acmeinsurance.common.Constants.JSON_DISPLAY_NAME;
import static com.acmeinsurance.common.Constants.JSON_DN;
import static com.acmeinsurance.common.Constants.JSON_EDITABLE;
import static com.acmeinsurance.common.Constants.JSON_ENTRY;
import static com.acmeinsurance.common.Constants.JSON_ID;
import static com.acmeinsurance.common.Constants.JSON_ITEMS;
import static com.acmeinsurance.common.Constants.JSON_KIND;
import static com.acmeinsurance.common.Constants.JSON_KIND_LABEL;
import static com.acmeinsurance.common.Constants.JSON_LABEL;
import static com.acmeinsurance.common.Constants.JSON_LABELS;
import static com.acmeinsurance.common.Constants.JSON_LIMIT;
import static com.acmeinsurance.common.Constants.JSON_LOCKED;
import static com.acmeinsurance.common.Constants.JSON_LOCKED_UNTIL_UNLOCKED;
import static com.acmeinsurance.common.Constants.JSON_MAIL;
import static com.acmeinsurance.common.Constants.JSON_MANAGED_BY;
import static com.acmeinsurance.common.Constants.JSON_META;
import static com.acmeinsurance.common.Constants.JSON_MODIFIED;
import static com.acmeinsurance.common.Constants.JSON_MODIFIED_BY;
import static com.acmeinsurance.common.Constants.JSON_PARENT_ID;
import static com.acmeinsurance.common.Constants.JSON_PARENT_LABEL;
import static com.acmeinsurance.common.Constants.JSON_PASSWORD_CHANGED;
import static com.acmeinsurance.common.Constants.JSON_PASSWORD_RESET;
import static com.acmeinsurance.common.Constants.JSON_RDN;
import static com.acmeinsurance.common.Constants.JSON_STATUS;
import static com.acmeinsurance.common.Constants.JSON_TEMPORARY_PASSWORD;
import static com.acmeinsurance.common.Constants.JSON_TRUNCATED;
import static com.acmeinsurance.common.Constants.JSON_UNDERWRITING_COMPANY;
import static com.acmeinsurance.common.Constants.LDAP_TRUE;
import static com.acmeinsurance.common.Constants.MAIL;
import static com.acmeinsurance.common.Constants.MODIFIERS_NAME;
import static com.acmeinsurance.common.Constants.MODIFY_TIMESTAMP;
import static com.acmeinsurance.common.Constants.OBJECT_CLASS;
import static com.acmeinsurance.common.Constants.OC_ACME_SYNC_MANAGED;
import static com.acmeinsurance.common.Constants.PARAM_ID;
import static com.acmeinsurance.common.Constants.PARAM_KIND;
import static com.acmeinsurance.common.Constants.PARAM_KINDS;
import static com.acmeinsurance.common.Constants.PARAM_PARENT;
import static com.acmeinsurance.common.Constants.PARAM_Q;
import static com.acmeinsurance.common.Constants.PERMANENT_LOCK;
import static com.acmeinsurance.common.Constants.PROPERTY_SESSION;
import static com.acmeinsurance.common.Constants.PWD_ACCOUNT_LOCKED_TIME;
import static com.acmeinsurance.common.Constants.PWD_CHANGED_TIME;
import static com.acmeinsurance.common.Constants.PWD_FAILURE_TIME;
import static com.acmeinsurance.common.Constants.PWD_POLICY_SUBENTRY;
import static com.acmeinsurance.common.Constants.PWD_RESET;
import static com.acmeinsurance.common.Constants.RDN_ACCOUNTS;
import static com.acmeinsurance.common.Constants.RDN_AGENCIES;
import static com.acmeinsurance.common.Constants.RDN_FEATURES;
import static com.acmeinsurance.common.Constants.RDN_POLICIES;
import static com.acmeinsurance.common.Constants.SN;
import static com.acmeinsurance.common.Constants.SOURCE_CSV;
import static com.acmeinsurance.common.Constants.SOURCE_CSV_LEGACY_PREFIX;
import static com.acmeinsurance.common.Constants.SOURCE_MANUAL;
import static com.acmeinsurance.common.Constants.SOURCE_SALESFORCE;
import static com.acmeinsurance.common.Constants.UID;
import static com.acmeinsurance.common.Constants.USER_PASSWORD;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.apache.camel.Body;
import org.apache.camel.ExchangeProperty;
import org.apache.camel.Header;

import com.acmeinsurance.common.web.ApiException;
import com.acmeinsurance.directory.DirectoryConfig;
import com.acmeinsurance.directory.auth.Session;
import com.acmeinsurance.directory.db.AuditLog;
import com.acmeinsurance.directory.ldap.Ldap;
import com.acmeinsurance.directory.model.FieldDef;
import com.acmeinsurance.directory.model.FieldType;
import com.acmeinsurance.directory.model.KindDef;
import com.acmeinsurance.directory.model.Kinds;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.DN;
import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;
import com.unboundid.ldap.sdk.RDN;
import com.unboundid.ldap.sdk.ResultCode;
import com.unboundid.ldap.sdk.SearchResultEntry;
import com.unboundid.ldap.sdk.SearchScope;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;

/**
 * Create, read, update and delete for every kind of entry, plus lock, unlock and
 * password reset for people.
 *
 * <p>Ownership follows the sync's rule: an entry whose acmeSource names a sync
 * (salesforce, csv) belongs to that sync, which would undo changes made here. Such
 * entries are read-only, apart from the attributes the Salesforce sync never
 * touches (an agent's feature grants; an agreement's underwriting company unless
 * the sync manages it). Entries created here get acmeSource=manual. Lock, unlock
 * and password reset work on every person.
 */
@ApplicationScoped
@Named("entries")
public class EntryService {

    private static final String[] OPERATIONAL = {
        PWD_ACCOUNT_LOCKED_TIME, PWD_RESET, PWD_CHANGED_TIME, PWD_FAILURE_TIME, CREATE_TIMESTAMP,
        MODIFY_TIMESTAMP, MODIFIERS_NAME,
    };
    private static final String PASSWORD_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    private static final String ACTOR_UNKNOWN = "unknown";
    /** Audit log actions. */
    private static final String ACTION_CREATE = "create";
    private static final String ACTION_UPDATE = "update";
    private static final String ACTION_DELETE = "delete";
    private static final String ACTION_LOCK = "lock";
    private static final String ACTION_UNLOCK = "unlock";
    private static final String ACTION_PASSWORD_RESET = "password-reset";
    /** Most entries a DN picker offers per kind. */
    private static final int LOOKUP_LIMIT = 25;

    private final Ldap ldap;
    private final Kinds kinds;
    private final AuditLog audit;
    private final int listLimit;
    private final SecureRandom random = new SecureRandom();

    public EntryService(final Ldap ldap, final Kinds kinds, final AuditLog audit,
                        final DirectoryConfig cfg) {
        this.ldap = ldap;
        this.kinds = kinds;
        this.audit = audit;
        this.listLimit = cfg.query().listLimit();
    }

    // ---- reading ------------------------------------------------------------

    public Collection<KindDef> kinds() {
        return kinds.all();
    }

    /**
     * Entries of a kind directly below their container (or below {@code parentId}
     * for agreements, books and permissions), optionally filtered by {@code q}.
     */
    public Map<String, Object> list(@Header(PARAM_KIND) final String kindId, @Header(PARAM_Q) final String q,
                                    @Header(PARAM_PARENT) final String parentId) throws LDAPException {
        final KindDef k = kind(kindId);
        final String base = container(k, parentId);
        Filter f = Filter.createEqualityFilter(OBJECT_CLASS, k.structuralClass());
        if (q != null && !q.isBlank()) {
            final List<Filter> any = new ArrayList<>();
            for (final String attr : searchAttributes(k)) {
                any.add(Filter.createSubstringFilter(attr, null, new String[] {q.trim()}, null));
            }
            f = Filter.createANDFilter(f, Filter.createORFilter(any));
        }
        final Ldap.Page page = ldap.search(base, SearchScope.ONE, f, listLimit, k.rdnAttribute(), CN, DISPLAY_NAME,
                MAIL, DESCRIPTION, ACME_SOURCE, ACME_DEACTIVATED, PWD_ACCOUNT_LOCKED_TIME,
                ACME_UNDERWRITING_COMPANY_DN);
        final List<Map<String, Object>> items = new ArrayList<>();
        for (final SearchResultEntry e : page.entries()) {
            items.add(summary(k, e));
        }
        items.sort(Comparator.comparing(m -> String.valueOf(m.get(JSON_RDN)).toLowerCase(Locale.ROOT)));
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put(JSON_KIND, k.id());
        out.put(JSON_PARENT_ID, parentId);
        out.put(JSON_PARENT_LABEL, parentId == null ? null : label(Ids.decode(parentId)));
        out.put(JSON_TRUNCATED, page.truncated());
        out.put(JSON_LIMIT, listLimit);
        out.put(JSON_ITEMS, items);
        return out;
    }

    public Map<String, Object> get(@Header(PARAM_KIND) final String kindId, @Header(PARAM_ID) final String id)
            throws LDAPException {
        final KindDef k = kind(kindId);
        return view(k, load(k, id));
    }

    /** Entries of the given kinds whose cn or display name contains {@code q}: the DN pickers. */
    public List<Map<String, Object>> lookup(@Header(PARAM_KINDS) final String kindIds, @Header(PARAM_Q) final String q)
            throws LDAPException {
        final List<Map<String, Object>> out = new ArrayList<>();
        if (kindIds == null || kindIds.isBlank()) {
            return out;
        }
        for (final String kindId : kindIds.split(",")) {
            final KindDef k = kind(kindId.trim());
            Filter f = Filter.createEqualityFilter(OBJECT_CLASS, k.structuralClass());
            if (q != null && !q.isBlank()) {
                final List<Filter> any = new ArrayList<>();
                for (final String attr : searchAttributes(k)) {
                    any.add(Filter.createSubstringFilter(attr, null, new String[] {q.trim()}, null));
                }
                f = Filter.createANDFilter(f, Filter.createORFilter(any));
            }
            for (final SearchResultEntry e : ldap.search(root(k), SearchScope.SUB, f, LOOKUP_LIMIT, CN, DISPLAY_NAME,
                    UID).entries()) {
                final Map<String, Object> m = new LinkedHashMap<>();
                m.put(JSON_ID, Ids.encode(e.getDN()));
                m.put(JSON_DN, e.getDN());
                m.put(JSON_KIND, k.id());
                m.put(JSON_KIND_LABEL, k.label());
                m.put(JSON_LABEL, label(e.getDN()));
                m.put(JSON_DISPLAY_NAME, e.getAttributeValue(DISPLAY_NAME));
                out.add(m);
            }
        }
        out.sort(Comparator.comparing(m -> String.valueOf(m.get(JSON_LABEL)).toLowerCase(Locale.ROOT)));
        return out;
    }

    // ---- changing -----------------------------------------------------------

    public Map<String, Object> create(@ExchangeProperty(PROPERTY_SESSION) final Session session,
                                      @Header(PARAM_KIND) final String kindId, @Body final Map<String, Object> body)
            throws LDAPException {
        final KindDef k = kind(kindId);
        final Map<String, List<String>> values = values(k, body);
        for (final FieldDef f : k.fields()) {
            if (f.required() && values.getOrDefault(f.attribute(), List.of()).isEmpty()) {
                throw ApiException.badRequest(f.label() + " is required");
            }
        }
        if (k.person() && values.getOrDefault(CN, List.of()).isEmpty()) {
            values.put(CN, List.of(fullName(values.get(GIVEN_NAME), values.get(SN))));
        }
        final String rdnValue = single(values.get(k.rdnAttribute()), k.rdnAttribute());
        final String parentId = body == null ? null : (String) body.get(JSON_PARENT_ID);
        final DN dn = new DN(new RDN(k.rdnAttribute(), rdnValue), new DN(container(k, parentId)));

        final Entry e = new Entry(dn);
        final List<String> classes = new ArrayList<>(k.objectClasses());
        if (k.sourceTracked()) {
            classes.add(OC_ACME_SYNC_MANAGED);
            e.addAttribute(ACME_SOURCE, SOURCE_MANUAL);
        }
        e.addAttribute(OBJECT_CLASS, classes);
        values.forEach((attr, vals) -> {
            if (!vals.isEmpty()) {
                e.addAttribute(attr, vals);
            }
        });
        if (k.person()) {
            e.addAttribute(ACME_USER_TYPE, k.userType());
            if (k.passwordPolicy() != null) {
                e.addAttribute(PWD_POLICY_SUBENTRY, k.passwordPolicy() + "," + RDN_POLICIES + "," + ldap.baseDn());
            }
        }
        ldap.add(e);
        audit.record(actor(session), ACTION_CREATE, k.id(), dn.toString(), "attributes: " + String.join(", ",
                values.entrySet().stream().filter(x -> !x.getValue().isEmpty()).map(Map.Entry::getKey).toList()));
        return view(k, load(k, Ids.encode(dn.toString())));
    }

    /**
     * Changes the attributes present in the body ({"attributes": {name: [values]}});
     * an empty list removes the attribute, and an attribute left out is unchanged.
     */
    public Map<String, Object> update(@ExchangeProperty(PROPERTY_SESSION) final Session session,
                                      @Header(PARAM_KIND) final String kindId, @Header(PARAM_ID) final String id,
                                      @Body final Map<String, Object> body) throws LDAPException {
        final KindDef k = kind(kindId);
        final SearchResultEntry e = load(k, id);
        final String owner = owner(e);
        final Set<String> editable = editable(k, owner);
        final Map<String, List<String>> wanted = values(k, body);
        if (k.person() && wanted.containsKey(CN) && wanted.get(CN).isEmpty()) {
            wanted.put(CN, List.of(fullName(wanted.getOrDefault(GIVEN_NAME, values(e, GIVEN_NAME)),
                    wanted.getOrDefault(SN, values(e, SN)))));
        }
        final List<Modification> mods = new ArrayList<>();
        final List<String> changed = new ArrayList<>();
        for (final Map.Entry<String, List<String>> w : wanted.entrySet()) {
            final FieldDef f = k.field(w.getKey()).orElseThrow();
            final List<String> have = values(e, f.attribute());
            if (same(f, have, w.getValue())) {
                continue;
            }
            if (f.attribute().equalsIgnoreCase(k.rdnAttribute())) {
                throw ApiException.badRequest(f.label() + " cannot be changed");
            }
            if (f.required() && w.getValue().isEmpty()) {
                throw ApiException.badRequest(f.label() + " is required");
            }
            if (!editable.contains(f.attribute())) {
                throw ApiException.conflict(f.label() + " is maintained by " + sourceName(owner)
                        + "; change it there");
            }
            mods.add(w.getValue().isEmpty()
                    ? new Modification(ModificationType.DELETE, f.attribute())
                    : new Modification(ModificationType.REPLACE, f.attribute(), w.getValue().toArray(String[]::new)));
            changed.add(f.attribute());
        }
        if (!mods.isEmpty()) {
            ldap.modify(e.getDN(), mods);
            audit.record(actor(session), ACTION_UPDATE, k.id(), e.getDN(), "changed: " + String.join(", ", changed));
        }
        return view(k, load(k, id));
    }

    public Map<String, Object> delete(@ExchangeProperty(PROPERTY_SESSION) final Session session,
                                      @Header(PARAM_KIND) final String kindId, @Header(PARAM_ID) final String id)
            throws LDAPException {
        final KindDef k = kind(kindId);
        final SearchResultEntry e = load(k, id);
        final String owner = owner(e);
        if (owner != null) {
            throw ApiException.conflict("This entry is maintained by " + sourceName(owner)
                    + "; remove it there, or the next sync recreates it");
        }
        ldap.delete(e.getDN());
        audit.record(actor(session), ACTION_DELETE, k.id(), e.getDN(), null);
        return Map.of(JSON_DELETED, true, JSON_DN, e.getDN());
    }

    /** Locks a person until unlocked (the same permanent lock the syncs use). */
    public Map<String, Object> lock(@ExchangeProperty(PROPERTY_SESSION) final Session session,
                                    @Header(PARAM_KIND) final String kindId, @Header(PARAM_ID) final String id)
            throws LDAPException {
        final KindDef k = personKind(kindId);
        final SearchResultEntry e = load(k, id);
        if (!PERMANENT_LOCK.equals(e.getAttributeValue(PWD_ACCOUNT_LOCKED_TIME))) {
            ldap.modify(e.getDN(), List.of(
                    new Modification(ModificationType.REPLACE, PWD_ACCOUNT_LOCKED_TIME, PERMANENT_LOCK)));
            audit.record(actor(session), ACTION_LOCK, k.id(), e.getDN(), null);
        }
        return view(k, load(k, id));
    }

    /** Clears a lock (set here or by too many failed sign-ins), unless the person's source deactivated them. */
    public Map<String, Object> unlock(@ExchangeProperty(PROPERTY_SESSION) final Session session,
                                      @Header(PARAM_KIND) final String kindId, @Header(PARAM_ID) final String id)
            throws LDAPException {
        final KindDef k = personKind(kindId);
        final SearchResultEntry e = load(k, id);
        if (e.hasAttribute(ACME_DEACTIVATED)) {
            throw ApiException.conflict("Deactivated by " + sourceName(owner(e))
                    + ", which would lock them again; reactivate them there");
        }
        final List<Modification> mods = new ArrayList<>();
        if (e.hasAttribute(PWD_ACCOUNT_LOCKED_TIME)) {
            mods.add(new Modification(ModificationType.DELETE, PWD_ACCOUNT_LOCKED_TIME));
        }
        if (e.hasAttribute(PWD_FAILURE_TIME)) {
            mods.add(new Modification(ModificationType.DELETE, PWD_FAILURE_TIME));
        }
        if (!mods.isEmpty()) {
            ldap.modify(e.getDN(), mods);
            audit.record(actor(session), ACTION_UNLOCK, k.id(), e.getDN(), null);
        }
        return view(k, load(k, id));
    }

    /**
     * Gives a person a random temporary password, which they must change at their
     * next sign-in: userPassword and pwdReset are set in one operation (svc-directory
     * has write, not manage, so ppolicy does not set pwdReset by itself). The
     * password is returned once and never stored or logged.
     */
    public Map<String, Object> resetPassword(@ExchangeProperty(PROPERTY_SESSION) final Session session,
                                             @Header(PARAM_KIND) final String kindId, @Header(PARAM_ID) final String id)
            throws LDAPException {
        final KindDef k = personKind(kindId);
        final SearchResultEntry e = load(k, id);
        final String password = temporaryPassword();
        ldap.modify(e.getDN(), List.of(
                new Modification(ModificationType.REPLACE, USER_PASSWORD, password),
                new Modification(ModificationType.REPLACE, PWD_RESET, LDAP_TRUE)));
        audit.record(actor(session), ACTION_PASSWORD_RESET, k.id(), e.getDN(), null);
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put(JSON_TEMPORARY_PASSWORD, password);
        out.put(JSON_ENTRY, view(k, load(k, id)));
        return out;
    }

    // ---- helpers --------------------------------------------------------------

    private KindDef kind(final String id) {
        return kinds.find(id).orElseThrow(() -> ApiException.notFound("no such kind of entry: " + id));
    }

    private KindDef personKind(final String id) {
        final KindDef k = kind(id);
        if (!k.person()) {
            throw ApiException.badRequest(k.plural() + " have no password or lock");
        }
        return k;
    }

    /** Where a kind's entries live: its fixed container, or the given parent entry. */
    private String container(final KindDef k, final String parentId) throws LDAPException {
        if (k.baseRdn() != null) {
            return k.baseRdn() + "," + ldap.baseDn();
        }
        if (parentId == null || parentId.isBlank()) {
            throw ApiException.badRequest(k.plural() + " need a parent " + kind(k.parentKind()).label().toLowerCase(
                    Locale.ROOT));
        }
        final String parentDn = Ids.decode(parentId);
        final SearchResultEntry parent = ldap.get(parentDn, OBJECT_CLASS);
        if (parent == null || !parent.hasObjectClass(kind(k.parentKind()).structuralClass())) {
            throw ApiException.notFound("no such " + kind(k.parentKind()).label().toLowerCase(Locale.ROOT));
        }
        return parent.getDN();
    }

    /** The fixed container a kind's tree hangs from (agreements and books: ou=agencies). */
    private String root(final KindDef k) {
        KindDef top = k;
        while (top.baseRdn() == null) {
            top = kind(top.parentKind());
        }
        return top.baseRdn() + "," + ldap.baseDn();
    }

    private SearchResultEntry load(final KindDef k, final String id) throws LDAPException {
        final String dn = Ids.decode(id);
        final String[] attrs = new String[OPERATIONAL.length + 1];
        attrs[0] = ALL_USER_ATTRIBUTES;
        System.arraycopy(OPERATIONAL, 0, attrs, 1, OPERATIONAL.length);
        final SearchResultEntry e;
        try {
            e = ldap.get(dn, attrs);
        } catch (final LDAPException ex) {
            if (ex.getResultCode() == ResultCode.INVALID_DN_SYNTAX) {
                throw ApiException.notFound("no such entry");
            }
            throw ex;
        }
        if (e == null || !e.hasObjectClass(k.structuralClass()) || !placed(k, e)) {
            throw ApiException.notFound("no such " + k.label().toLowerCase(Locale.ROOT));
        }
        return e;
    }

    /** Whether the entry sits where entries of its kind live. */
    private boolean placed(final KindDef k, final SearchResultEntry e) throws LDAPException {
        final String parent = e.getParentDNString();
        if (k.baseRdn() != null) {
            return parent != null && new DN(parent).equals(new DN(k.baseRdn() + "," + ldap.baseDn()));
        }
        final SearchResultEntry p = parent == null ? null : ldap.get(parent, OBJECT_CLASS);
        return p != null && p.hasObjectClass(kind(k.parentKind()).structuralClass());
    }

    private Map<String, Object> summary(final KindDef k, final SearchResultEntry e) throws LDAPException {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put(JSON_ID, Ids.encode(e.getDN()));
        m.put(JSON_DN, e.getDN());
        m.put(JSON_RDN, e.getAttributeValue(k.rdnAttribute()));
        m.put(JSON_CN, e.getAttributeValue(CN));
        m.put(JSON_DISPLAY_NAME, e.getAttributeValue(DISPLAY_NAME));
        m.put(JSON_MAIL, e.getAttributeValue(MAIL));
        m.put(JSON_DESCRIPTION, e.getAttributeValue(DESCRIPTION));
        m.put(JSON_MANAGED_BY, owner(e));
        m.put(JSON_LOCKED, e.hasAttribute(PWD_ACCOUNT_LOCKED_TIME));
        m.put(JSON_DEACTIVATED, e.hasAttribute(ACME_DEACTIVATED));
        final String company = e.getAttributeValue(ACME_UNDERWRITING_COMPANY_DN);
        m.put(JSON_UNDERWRITING_COMPANY, company == null ? null : label(company));
        return m;
    }

    private Map<String, Object> view(final KindDef k, final SearchResultEntry e) throws LDAPException {
        final String owner = owner(e);
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put(JSON_ID, Ids.encode(e.getDN()));
        m.put(JSON_DN, e.getDN());
        m.put(JSON_KIND, k.id());
        m.put(JSON_RDN, e.getAttributeValue(k.rdnAttribute()));
        m.put(JSON_LABEL, label(e.getDN()));
        if (k.parentKind() != null) {
            m.put(JSON_PARENT_ID, Ids.encode(e.getParentDNString()));
            m.put(JSON_PARENT_LABEL, label(e.getParentDNString()));
        }
        m.put(JSON_MANAGED_BY, owner);
        m.put(JSON_EDITABLE, List.copyOf(editable(k, owner)));
        m.put(JSON_DELETABLE, owner == null);

        final Map<String, List<String>> attrs = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        final Map<String, String> labels = new LinkedHashMap<>();
        for (final Attribute a : e.getAttributes()) {
            final String name = k.field(a.getBaseName()).map(FieldDef::attribute).orElse(a.getBaseName());
            if (USER_PASSWORD.equalsIgnoreCase(name) || List.of(OPERATIONAL).contains(name)) {
                continue;
            }
            attrs.put(name, List.of(a.getValues()));
            if (k.field(name).map(FieldDef::isDn).orElse(false)) {
                for (final String v : a.getValues()) {
                    labels.put(v, label(v));
                }
            }
        }
        m.put(JSON_ATTRIBUTES, attrs);
        m.put(JSON_LABELS, labels);
        if (k.person()) {
            final Map<String, Object> status = new LinkedHashMap<>();
            final String locked = e.getAttributeValue(PWD_ACCOUNT_LOCKED_TIME);
            status.put(JSON_LOCKED, locked != null);
            status.put(JSON_LOCKED_UNTIL_UNLOCKED, PERMANENT_LOCK.equals(locked));
            status.put(JSON_PASSWORD_RESET, LDAP_TRUE.equalsIgnoreCase(e.getAttributeValue(PWD_RESET)));
            status.put(JSON_DEACTIVATED, e.getAttributeValue(ACME_DEACTIVATED));
            status.put(JSON_PASSWORD_CHANGED, e.getAttributeValue(PWD_CHANGED_TIME));
            m.put(JSON_STATUS, status);
        }
        final Map<String, Object> meta = new LinkedHashMap<>();
        meta.put(JSON_CREATED, e.getAttributeValue(CREATE_TIMESTAMP));
        meta.put(JSON_MODIFIED, e.getAttributeValue(MODIFY_TIMESTAMP));
        meta.put(JSON_MODIFIED_BY, e.getAttributeValue(MODIFIERS_NAME));
        m.put(JSON_META, meta);
        return m;
    }

    /**
     * A readable name for a DN: the path below ou=agencies or ou=features
     * ("AG0042 / AGR-0042-01"), otherwise the first RDN value.
     */
    String label(final String dn) {
        try {
            final DN d = new DN(dn);
            for (final String top : List.of(RDN_AGENCIES + "," + RDN_ACCOUNTS, RDN_FEATURES)) {
                final DN base = new DN(top + "," + ldap.baseDn());
                if (d.isDescendantOf(base, false)) {
                    final List<String> parts = new ArrayList<>();
                    for (DN x = d; x != null && !x.equals(base); x = x.getParent()) {
                        parts.addFirst(x.getRDN().getAttributeValues()[0]);
                    }
                    return String.join(" / ", parts);
                }
            }
            return d.getRDN() == null ? dn : d.getRDN().getAttributeValues()[0];
        } catch (final LDAPException e) {
            return dn;
        }
    }

    /** The sync that owns the entry (salesforce or csv), or null for hand-made entries. */
    static String owner(final SearchResultEntry e) {
        final String source = e.getAttributeValue(ACME_SOURCE);
        if (source == null || source.isBlank() || SOURCE_MANUAL.equalsIgnoreCase(source)) {
            return null;
        }
        final String s = source.toLowerCase(Locale.ROOT);
        return s.startsWith(SOURCE_CSV_LEGACY_PREFIX) ? SOURCE_CSV : s;
    }

    private static Set<String> editable(final KindDef k, final String owner) {
        if (owner == null) {
            final Set<String> all = new LinkedHashSet<>();
            for (final FieldDef f : k.fields()) {
                if (f.type() != FieldType.READONLY && !f.attribute().equalsIgnoreCase(k.rdnAttribute())) {
                    all.add(f.attribute());
                }
            }
            return all;
        }
        return SOURCE_SALESFORCE.equals(owner) ? k.editableWhenSalesforce() : Set.of();
    }

    private static String sourceName(final String owner) {
        if (SOURCE_SALESFORCE.equals(owner)) {
            return "Salesforce";
        }
        return SOURCE_CSV.equals(owner) ? "the CSV feed" : "its source (" + owner + ")";
    }

    /** The form values in a request body, for the kind's editable fields only, trimmed, empties dropped. */
    private static Map<String, List<String>> values(final KindDef k, final Map<String, Object> body) {
        final Map<String, List<String>> out = new LinkedHashMap<>();
        if (body == null || !(body.get(JSON_ATTRIBUTES) instanceof final Map<?, ?> attrs)) {
            return out;
        }
        for (final Map.Entry<?, ?> a : attrs.entrySet()) {
            final String name = String.valueOf(a.getKey());
            final FieldDef f = k.field(name).orElseThrow(() -> ApiException.badRequest(
                    k.label() + " has no field " + name));
            if (f.type() == FieldType.READONLY) {
                continue;
            }
            final List<String> vals = new ArrayList<>();
            final Collection<?> raw = a.getValue() instanceof final Collection<?> c ? c
                    : a.getValue() == null ? List.of() : List.of(a.getValue());
            for (final Object o : raw) {
                final String v = o == null ? "" : String.valueOf(o).trim();
                if (!v.isEmpty() && !vals.contains(v)) {
                    vals.add(v);
                }
            }
            if (!f.multiValued() && vals.size() > 1) {
                throw ApiException.badRequest(f.label() + " takes one value");
            }
            if (f.isDn()) {
                for (final String v : vals) {
                    if (!DN.isValidDN(v)) {
                        throw ApiException.badRequest(f.label() + ": not a DN: " + v);
                    }
                }
            }
            out.put(f.attribute(), vals);
        }
        return out;
    }

    private static List<String> values(final SearchResultEntry e, final String attr) {
        final String[] v = e.getAttributeValues(attr);
        return v == null ? List.of() : List.of(v);
    }

    private static boolean same(final FieldDef f, final List<String> have, final List<String> want) {
        if (have.size() != want.size()) {
            return false;
        }
        if (f.isDn()) {
            final Set<String> a = new LinkedHashSet<>();
            final Set<String> b = new LinkedHashSet<>();
            have.forEach(v -> a.add(normalize(v)));
            want.forEach(v -> b.add(normalize(v)));
            return a.equals(b);
        }
        return Set.copyOf(have).equals(Set.copyOf(want));
    }

    private static String normalize(final String dn) {
        try {
            return new DN(dn).toNormalizedString();
        } catch (final LDAPException e) {
            return dn;
        }
    }

    private static List<String> searchAttributes(final KindDef k) {
        return k.person() ? List.of(UID, CN, MAIL) : List.of(CN, DISPLAY_NAME);
    }

    private static String single(final List<String> vals, final String attr) {
        if (vals == null || vals.isEmpty()) {
            throw ApiException.badRequest(attr + " is required");
        }
        return vals.getFirst();
    }

    private static String fullName(final List<String> given, final List<String> sn) {
        final String g = given == null || given.isEmpty() ? "" : given.getFirst();
        final String s = sn == null || sn.isEmpty() ? "" : sn.getFirst();
        return (g + " " + s).trim();
    }

    /** 4 groups of 4 letters and digits, e.g. Xk7m-Pq3r-Ty8w-Nz2v: easy to read out, 90+ bits. */
    private String temporaryPassword() {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            if (i > 0 && i % 4 == 0) {
                sb.append('-');
            }
            sb.append(PASSWORD_ALPHABET.charAt(random.nextInt(PASSWORD_ALPHABET.length())));
        }
        return sb.toString();
    }

    private static String actor(final Session session) {
        return session == null || session.username() == null ? ACTOR_UNKNOWN : session.username();
    }
}
