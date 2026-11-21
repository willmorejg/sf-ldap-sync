package com.acmeinsurance.directory.service;

import static com.acmeinsurance.common.Constants.ALL_USER_ATTRIBUTES;
import static com.acmeinsurance.common.Constants.JSON_ATTRIBUTES;
import static com.acmeinsurance.common.Constants.JSON_BASE_DN;
import static com.acmeinsurance.common.Constants.JSON_COUNT;
import static com.acmeinsurance.common.Constants.JSON_DELETED;
import static com.acmeinsurance.common.Constants.JSON_DESCRIPTION;
import static com.acmeinsurance.common.Constants.JSON_DN;
import static com.acmeinsurance.common.Constants.JSON_ENTRIES;
import static com.acmeinsurance.common.Constants.JSON_FILTER;
import static com.acmeinsurance.common.Constants.JSON_MILLIS;
import static com.acmeinsurance.common.Constants.JSON_NAME;
import static com.acmeinsurance.common.Constants.JSON_OBJECT_CLASSES;
import static com.acmeinsurance.common.Constants.JSON_SCOPE;
import static com.acmeinsurance.common.Constants.JSON_SINGLE_VALUED;
import static com.acmeinsurance.common.Constants.JSON_SIZE_LIMIT;
import static com.acmeinsurance.common.Constants.JSON_TRUNCATED;
import static com.acmeinsurance.common.Constants.PARAM_ID;
import static com.acmeinsurance.common.Constants.PROPERTY_SESSION;
import static com.acmeinsurance.common.Constants.USER_PASSWORD;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import org.apache.camel.Body;
import org.apache.camel.ExchangeProperty;
import org.apache.camel.Header;

import com.acmeinsurance.common.web.ApiException;
import com.acmeinsurance.directory.DirectoryConfig;
import com.acmeinsurance.directory.auth.Session;
import com.acmeinsurance.directory.db.SavedQueries;
import com.acmeinsurance.common.ldap.DnUtil;
import com.acmeinsurance.directory.ldap.Ldap;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.DN;
import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.SearchResultEntry;
import com.unboundid.ldap.sdk.SearchScope;
import com.unboundid.ldap.sdk.schema.AttributeTypeDefinition;
import com.unboundid.ldap.sdk.schema.ObjectClassDefinition;
import com.unboundid.ldap.sdk.schema.Schema;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;

/**
 * The developer query page: runs read-only searches as svc-directory (so the
 * directory's ACLs decide what is visible; passwords never are), lists the schema
 * for the query builder, and keeps saved queries in SQLite.
 */
@ApplicationScoped
@Named("queries")
public class QueryService {

    private static final String SCOPE_BASE = "base";
    private static final String SCOPE_ONE = "one";
    private static final String SCOPE_SUB = "sub";
    /** The filter used when none is given. */
    private static final String ANY_ENTRY = "(objectClass=*)";
    /** LDAP syntax of DN-valued attributes (RFC 4517). */
    private static final String DN_SYNTAX_OID = "1.3.6.1.4.1.1466.115.121.1.12";
    /** Attribute lists are separated by commas or white space. */
    private static final String LIST_SEPARATOR = "[,\\s]+";
    private static final String ACTOR_UNKNOWN = "unknown";
    private static final int DEFAULT_SIZE_LIMIT = 100;

    private final Ldap ldap;
    private final SavedQueries saved;
    private final int maxResults;

    public QueryService(final Ldap ldap, final SavedQueries saved, final DirectoryConfig cfg) {
        this.ldap = ldap;
        this.saved = saved;
        this.maxResults = cfg.query().maxResults();
    }

    /** Body: baseDn, scope (base, one, sub), filter, attributes (list), sizeLimit. */
    public Map<String, Object> run(@Body final Map<String, Object> body) throws LDAPException {
        final Request q = request(body);
        final int limit = Math.max(1, Math.min(maxResults, intValue(body.get(JSON_SIZE_LIMIT), DEFAULT_SIZE_LIMIT)));
        final long started = System.nanoTime();
        final Ldap.Page page = ldap.search(q.baseDn(), q.scope(), q.filter(), limit,
                q.attributes().isEmpty() ? new String[] {ALL_USER_ATTRIBUTES} : q.attributes().toArray(String[]::new));
        final List<Map<String, Object>> entries = new ArrayList<>();
        for (final SearchResultEntry e : page.entries()) {
            final Map<String, List<String>> attrs = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (final Attribute a : e.getAttributes()) {
                if (!USER_PASSWORD.equalsIgnoreCase(a.getBaseName())) {
                    attrs.put(a.getName(), List.of(a.getValues()));
                }
            }
            final Map<String, Object> m = new LinkedHashMap<>();
            m.put(JSON_DN, e.getDN());
            m.put(JSON_ATTRIBUTES, attrs);
            entries.add(m);
        }
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put(JSON_FILTER, q.filter().toString());
        out.put(JSON_COUNT, entries.size());
        out.put(JSON_TRUNCATED, page.truncated());
        out.put(JSON_SIZE_LIMIT, limit);
        out.put(JSON_MILLIS, (System.nanoTime() - started) / 1_000_000);
        out.put(JSON_ENTRIES, entries);
        return out;
    }

    /** Attribute types and object classes from the directory's schema, for the builder's pickers. */
    public Map<String, Object> schema() throws LDAPException {
        final Schema schema = ldap.schema();
        final List<Map<String, Object>> attrs = new ArrayList<>();
        final TreeSet<String> classes = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        if (schema != null) {
            for (final AttributeTypeDefinition a : schema.getAttributeTypes()) {
                if (a.isOperational() || a.isObsolete()) {
                    continue;
                }
                final Map<String, Object> m = new LinkedHashMap<>();
                m.put(JSON_NAME, a.getNameOrOID());
                m.put(JSON_DESCRIPTION, a.getDescription());
                m.put(JSON_DN, DN_SYNTAX_OID.equals(a.getSyntaxOID(schema)));
                m.put(JSON_SINGLE_VALUED, a.isSingleValued());
                attrs.add(m);
            }
            for (final ObjectClassDefinition c : schema.getObjectClasses()) {
                classes.add(c.getNameOrOID());
            }
        }
        attrs.sort((a, b) -> String.valueOf(a.get(JSON_NAME)).compareToIgnoreCase(String.valueOf(b.get(JSON_NAME))));
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put(JSON_BASE_DN, ldap.baseDn());
        out.put(JSON_ATTRIBUTES, attrs);
        out.put(JSON_OBJECT_CLASSES, List.copyOf(classes));
        return out;
    }

    public List<SavedQueries.SavedQuery> savedQueries() throws SQLException {
        return saved.all();
    }

    public SavedQueries.SavedQuery save(@ExchangeProperty(PROPERTY_SESSION) final Session session,
                                        @Body final Map<String, Object> body) throws SQLException {
        final Request q = request(body);
        final String name = text(body.get(JSON_NAME));
        if (name == null) {
            throw ApiException.badRequest("name is required");
        }
        return saved.add(new SavedQueries.SavedQuery(0, name, text(body.get(JSON_DESCRIPTION)), q.baseDn(),
                q.scope().getName(), q.filter().toString(), String.join(",", q.attributes()),
                session == null ? ACTOR_UNKNOWN : session.username(), null));
    }

    public Map<String, Object> delete(@Header(PARAM_ID) final String id) throws SQLException {
        final long n;
        try {
            n = Long.parseLong(id);
        } catch (final NumberFormatException e) {
            throw ApiException.notFound("no such saved query");
        }
        if (!saved.delete(n)) {
            throw ApiException.notFound("no such saved query");
        }
        return Map.of(JSON_DELETED, true);
    }

    /** A validated query: the base must be inside the directory, the filter must parse. */
    record Request(String baseDn, SearchScope scope, Filter filter, List<String> attributes) {
    }

    Request request(final Map<String, Object> body) {
        if (body == null) {
            throw ApiException.badRequest("missing query");
        }
        final String base = text(body.get(JSON_BASE_DN)) == null ? ldap.baseDn() : text(body.get(JSON_BASE_DN));
        if (!DN.isValidDN(base) || !DnUtil.isWithin(base, ldap.baseDn())) {
            throw ApiException.badRequest("the base DN must be " + ldap.baseDn() + " or below it");
        }
        final String scopeName = text(body.get(JSON_SCOPE)) == null ? SCOPE_SUB : text(body.get(JSON_SCOPE));
        final SearchScope scope = switch (scopeName.toLowerCase(Locale.ROOT)) {
            case SCOPE_BASE -> SearchScope.BASE;
            case SCOPE_ONE -> SearchScope.ONE;
            case SCOPE_SUB -> SearchScope.SUB;
            default -> throw ApiException.badRequest("scope must be base, one or sub");
        };
        final String filterText = text(body.get(JSON_FILTER)) == null ? ANY_ENTRY : text(body.get(JSON_FILTER));
        final Filter filter;
        try {
            filter = Filter.create(filterText);
        } catch (final LDAPException e) {
            throw ApiException.badRequest("filter: " + e.getMessage());
        }
        final List<String> attrs = new ArrayList<>();
        if (body.get(JSON_ATTRIBUTES) instanceof final List<?> list) {
            for (final Object o : list) {
                final String a = text(o);
                if (a != null && !attrs.contains(a)) {
                    attrs.add(a);
                }
            }
        } else if (text(body.get(JSON_ATTRIBUTES)) != null) {
            for (final String a : text(body.get(JSON_ATTRIBUTES)).split(LIST_SEPARATOR)) {
                if (!a.isBlank() && !attrs.contains(a)) {
                    attrs.add(a);
                }
            }
        }
        return new Request(base, scope, filter, attrs);
    }

    private static String text(final Object o) {
        final String s = o == null ? null : String.valueOf(o).trim();
        return s == null || s.isEmpty() ? null : s;
    }

    private static int intValue(final Object o, final int fallback) {
        if (o instanceof final Number n) {
            return n.intValue();
        }
        try {
            return o == null ? fallback : Integer.parseInt(String.valueOf(o).trim());
        } catch (final NumberFormatException e) {
            return fallback;
        }
    }
}
