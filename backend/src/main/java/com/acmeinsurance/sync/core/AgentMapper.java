package com.acmeinsurance.sync.core;

import static com.acmeinsurance.common.Constants.CN;
import static com.acmeinsurance.common.Constants.GIVEN_NAME;
import static com.acmeinsurance.common.Constants.SN;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Turns Salesforce User records (brokers and agents) into {@link PersonSpec}s,
 * resolving their agency/agreement/book grants to directory DNs.
 */
public final class AgentMapper {

    /**
     * @param agencyField optional User field holding the agent's own agency ID
     *                    (e.g. Contact.AccountId); null to skip
     * @param attributes  LDAP attribute name to User field, e.g. mail=Email
     */
    public record Mapping(String idField, String uidField, String activeField, String contactField,
                          String agencyField, Map<String, String> attributes) {
    }

    public record Result(List<PersonSpec> people, int skipped, List<String> warnings) {
    }

    private AgentMapper() {
    }

    /**
     * Indexes junction records (e.g. AccountContactRelation) by contact ID.
     *
     * @return 18-character contact ID to 18-character account IDs
     */
    public static Map<String, Set<String>> indexEntitlements(final List<Map<String, Object>> records,
                                                             final String contactField, final String accountField) {
        final Map<String, Set<String>> m = new HashMap<>();
        for (final Map<String, Object> r : records) {
            final String contact = Records.path(r, contactField);
            final String account = Records.path(r, accountField);
            if (SalesforceIds.isValid(contact) && SalesforceIds.isValid(account)) {
                m.computeIfAbsent(SalesforceIds.to18(contact), k -> new LinkedHashSet<>()).add(SalesforceIds.to18(account));
            }
        }
        return m;
    }

    /**
     * @param entitlementsByContact from {@link #indexEntitlements}; may be empty
     * @param accountDnBySfId       directory DN of a synced account, or null if unknown
     */
    public static Result map(final List<Map<String, Object>> users,
                             final Map<String, Set<String>> entitlementsByContact,
                             final Mapping m, final Function<String, String> accountDnBySfId) {
        final List<PersonSpec> people = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        int skipped = 0;
        for (final Map<String, Object> r : users) {
            final String rawId = Records.path(r, m.idField());
            final String uid = Records.path(r, m.uidField());
            if (!SalesforceIds.isValid(rawId) || uid == null) {
                warnings.add("Skipped user record without a valid ID or " + m.uidField() + ": " + rawId);
                skipped++;
                continue;
            }
            final String id = SalesforceIds.to18(rawId);
            final boolean active = Records.bool(r, m.activeField(), true);

            final Map<String, List<String>> attrs = new LinkedHashMap<>();
            m.attributes().forEach((ldapAttr, field) -> {
                final String v = Records.path(r, field);
                attrs.put(ldapAttr, v == null ? List.of() : List.of(v));
            });
            if (attrs.getOrDefault(SN, List.of()).isEmpty()) {
                attrs.put(SN, List.of(uid));
            }
            if (attrs.getOrDefault(CN, List.of()).isEmpty()) {
                final String given = first(attrs.get(GIVEN_NAME));
                final String sn = first(attrs.get(SN));
                attrs.put(CN, List.of(given == null ? sn : given + " " + sn));
            }

            final Set<String> accountIds = new LinkedHashSet<>();
            if (m.agencyField() != null) {
                final String agency = Records.path(r, m.agencyField());
                if (SalesforceIds.isValid(agency)) {
                    accountIds.add(SalesforceIds.to18(agency));
                }
            }
            final String contact = SalesforceIds.normalize(validOrNull(Records.path(r, m.contactField())));
            if (contact != null) {
                accountIds.addAll(entitlementsByContact.getOrDefault(contact, Set.of()));
            }
            final Set<String> dns = new LinkedHashSet<>();
            for (final String accountId : accountIds) {
                final String dn = accountDnBySfId.apply(accountId);
                if (dn == null) {
                    warnings.add(uid + ": account " + accountId + " is not in the directory (not an agency/agreement/book, or rejected)");
                } else {
                    dns.add(dn);
                }
            }
            people.add(new PersonSpec(uid, id, active, attrs, dns, null));
        }
        return new Result(people, skipped, warnings);
    }

    private static String first(final List<String> values) {
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private static String validOrNull(final String id) {
        return SalesforceIds.isValid(id) ? id : null;
    }
}
