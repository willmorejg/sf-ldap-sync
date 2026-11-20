package com.acmeinsurance.ldapsync.csv;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.jboss.logging.Logger;

import com.acmeinsurance.ldapsync.SyncConfig;
import com.acmeinsurance.ldapsync.core.CsvRowMapper;
import com.acmeinsurance.ldapsync.core.Dns;
import com.acmeinsurance.ldapsync.core.PersonSpec;
import com.acmeinsurance.ldapsync.core.SalesforceIds;
import com.acmeinsurance.ldapsync.core.SyncReport;
import com.acmeinsurance.ldapsync.ldap.LdapDirectory;
import com.acmeinsurance.ldapsync.ldap.PersonWriter;
import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.SearchResultEntry;
import com.unboundid.ldap.sdk.SearchScope;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import jakarta.inject.Provider;

/**
 * Loads people who are not in Salesforce (home office staff, members/insureds and
 * other users) from CSV files dropped into inbox/internal, inbox/members or
 * inbox/other. Rows are upserts: a file can be re-dropped safely. A row with
 * active=false locks the entry; rows are never deleted. All three feeds write
 * acmeSource=csv.
 *
 * <p>Grants follow the user types in docs/LDAP_Schema_Definition.md: internal users
 * hold features (primary) and accounts (scope); members hold only policy
 * numbers; other users may hold any of the three.
 *
 * <p>This is the stand-in feed until those sources get their own routes (for
 * example Entra ID for staff, or the policy system for members).
 */
@ApplicationScoped
@Named("csvIntake")
public class CsvIntakeService {

    private static final Logger LOG = Logger.getLogger(CsvIntakeService.class);

    /** acmeSource of every entry the CSV feeds write. */
    public static final String SOURCE = "csv";

    private final SyncConfig cfg;
    private final Provider<LdapDirectory> directory;

    public CsvIntakeService(final SyncConfig cfg, final Provider<LdapDirectory> directory) {
        this.cfg = cfg;
        this.directory = directory;
    }

    /**
     * @param fileName path relative to the inbox, e.g. {@code members/2026-11-18.csv};
     *                 the folder decides the user type
     * @throws IllegalStateException if any row failed, so Camel moves the file to .failed
     */
    public Map<String, Object> load(final InputStream in, final String fileName) throws Exception {
        final String type = fileName.replace('\\', '/').split("/")[0];
        final PersonWriter.Profile profile = profileFor(type);
        final LdapDirectory dir = directory.get();
        final PersonWriter writer = new PersonWriter(dir, cfg.ldap().peopleDn());
        final SyncReport r = new SyncReport("csv " + fileName);
        Map<String, String> accountDnBySfId = null;

        final CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader().setSkipHeaderRecord(true).setTrim(true).setIgnoreEmptyLines(true).get();
        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
             CSVParser parser = format.parse(reader)) {
            final Map<String, String> header = CsvRowMapper.canonicalHeader(parser.getHeaderNames());
            for (final CSVRecord rec : parser) {
                final String line = "line " + (rec.getRecordNumber() + 1);
                try {
                    final Map<String, String> row = new LinkedHashMap<>();
                    header.forEach((col, canonical) -> row.put(canonical, rec.isSet(col) ? rec.get(col) : ""));
                    final CsvRowMapper.Row mapped = CsvRowMapper.map(row);
                    checkAllowed(type, mapped);
                    PersonSpec spec = mapped.spec();
                    if (mapped.featureRefs() != null) {
                        spec = spec.withFeatureDns(resolveFeatures(mapped.featureRefs()));
                    }
                    if (mapped.accountRefs() != null) {
                        if (accountDnBySfId == null && mapped.accountRefs().stream().anyMatch(s -> s.startsWith("sf:"))) {
                            accountDnBySfId = loadAccountIndex(dir);
                        }
                        spec = spec.withAccountDns(resolve(mapped.accountRefs(), accountDnBySfId));
                    }
                    writer.upsert(spec, profile, r);
                } catch (final LDAPException e) {
                    r.error(line + ": " + e.getExceptionMessage());
                } catch (final IllegalArgumentException e) {
                    r.error(line + ": " + e.getMessage());
                }
            }
        }
        r.finish();
        LOG.info(r);
        if (r.hasErrors()) {
            throw new IllegalStateException("CSV " + fileName + " finished with errors (good rows were applied): "
                    + r.toMap());
        }
        return r.toMap();
    }

    private PersonWriter.Profile profileFor(final String type) {
        final String people = cfg.ldap().peopleDn();
        final boolean adopt = cfg.safety().adoptUnowned();
        return switch (type) {
            case "internal" -> new PersonWriter.Profile(SOURCE, "internal", "ou=internal," + people,
                    List.of("inetOrgPerson", "acmeUser", "acmeFeatureAccess", "acmeAccountAccess"),
                    cfg.csv().internalPasswordPolicy(), dn -> true, true, adopt);
            case "members" -> new PersonWriter.Profile(SOURCE, "member", "ou=members," + people,
                    List.of("inetOrgPerson", "acmeUser", "acmeMemberAccess"), null, dn -> true, true, adopt);
            case "other" -> new PersonWriter.Profile(SOURCE, "other", "ou=other," + people,
                    List.of("inetOrgPerson", "acmeUser", "acmeFeatureAccess", "acmeAccountAccess", "acmeMemberAccess"), null,
                    dn -> true, true, adopt);
            default -> throw new IllegalArgumentException("Put CSV files in internal/, members/ or other/ "
                    + "(agents come from Salesforce), not '" + type + "'");
        };
    }

    /** Members hold policy numbers only; internal users have no policy grants. */
    private static void checkAllowed(final String type, final CsvRowMapper.Row row) {
        if ("members".equals(type) && (row.accountRefs() != null && !row.accountRefs().isEmpty()
                || row.featureRefs() != null && !row.featureRefs().isEmpty())) {
            throw new IllegalArgumentException("members hold policy numbers only, not account or feature grants");
        }
        if ("internal".equals(type) && row.spec().policyNumbers() != null && !row.spec().policyNumbers().isEmpty()) {
            throw new IllegalArgumentException("internal users do not hold policy numbers");
        }
    }

    /**
     * Feature references: {@code quoting} (a feature: all its permissions),
     * {@code quoting/read-quotes} (one permission) or a full DN under ou=features.
     * The directory's constraint overlay refuses a DN that is not a feature or permission.
     */
    private Set<String> resolveFeatures(final Set<String> refs) {
        final Set<String> dns = new LinkedHashSet<>();
        for (final String ref : refs) {
            if (ref.contains("=")) {
                dns.add(ref);
                continue;
            }
            final String[] parts = ref.split("/");
            if (parts.length > 2 || Arrays.stream(parts).anyMatch(String::isBlank)) {
                throw new IllegalArgumentException("feature reference '" + ref
                        + "' must be feature, feature/permission or a DN");
            }
            String dn = Dns.child("cn", parts[0].trim(), cfg.ldap().featuresDn());
            if (parts.length == 2) {
                dn = Dns.child("cn", parts[1].trim(), dn);
            }
            dns.add(dn);
        }
        return dns;
    }

    /**
     * Grant references: {@code sf:<Salesforce ID>} for an agency/agreement/book,
     * {@code client:<client ID>} for an entry under ou=clients, or a full DN.
     */
    private Set<String> resolve(final Set<String> refs, final Map<String, String> bySfId) {
        final Set<String> dns = new LinkedHashSet<>();
        for (final String ref : refs) {
            if (ref.startsWith("sf:")) {
                final String id = ref.substring(3).trim();
                final String dn = SalesforceIds.isValid(id) ? bySfId.get(SalesforceIds.to18(id)) : null;
                if (dn == null) {
                    throw new IllegalArgumentException("account " + ref + " is not in the directory");
                }
                dns.add(dn);
            } else if (ref.startsWith("client:")) {
                dns.add(Dns.child("cn", ref.substring(7).trim(), cfg.ldap().clientsDn()));
            } else if (ref.contains("=")) {
                dns.add(ref);
            } else {
                throw new IllegalArgumentException("account reference '" + ref + "' must be sf:<id>, client:<id> or a DN");
            }
        }
        return dns;
    }

    private Map<String, String> loadAccountIndex(final LdapDirectory dir) throws LDAPException {
        final Map<String, String> m = new HashMap<>();
        for (final SearchResultEntry e : dir.searchAll(cfg.ldap().accountsDn(), SearchScope.SUB,
                Filter.createPresenceFilter("acmeSalesforceId"), "acmeSalesforceId")) {
            m.put(e.getAttributeValue("acmeSalesforceId"), e.getDN());
        }
        return m;
    }
}
