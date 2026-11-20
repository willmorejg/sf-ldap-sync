package com.acmeinsurance.ldapsync.core;

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
    public static Map<String, Set<String>> indexEntitlements(List<Map<String, Object>> records,
                                                             String contactField, String accountField) {
        Map<String, Set<String>> m = new HashMap<>();
        for (Map<String, Object> r : records) {
            String contact = Records.path(r, contactField);
            String account = Records.path(r, accountField);
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
    public static Result map(List<Map<String, Object>> users, Map<String, Set<String>> entitlementsByContact,
                             Mapping m, Function<String, String> accountDnBySfId) {
        List<PersonSpec> people = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int skipped = 0;
        for (Map<String, Object> r : users) {
            String rawId = Records.path(r, m.idField());
            String uid = Records.path(r, m.uidField());
            if (!SalesforceIds.isValid(rawId) || uid == null) {
                warnings.add("Skipped user record without a valid ID or " + m.uidField() + ": " + rawId);
                skipped++;
                continue;
            }
            String id = SalesforceIds.to18(rawId);
            boolean active = Records.bool(r, m.activeField(), true);

            Map<String, List<String>> attrs = new LinkedHashMap<>();
            m.attributes().forEach((ldapAttr, field) -> {
                String v = Records.path(r, field);
                attrs.put(ldapAttr, v == null ? List.of() : List.of(v));
            });
            if (attrs.getOrDefault("sn", List.of()).isEmpty()) {
                attrs.put("sn", List.of(uid));
            }
            if (attrs.getOrDefault("cn", List.of()).isEmpty()) {
                String given = first(attrs.get("givenName"));
                String sn = first(attrs.get("sn"));
                attrs.put("cn", List.of(given == null ? sn : given + " " + sn));
            }

            Set<String> accountIds = new LinkedHashSet<>();
            if (m.agencyField() != null) {
                String agency = Records.path(r, m.agencyField());
                if (SalesforceIds.isValid(agency)) {
                    accountIds.add(SalesforceIds.to18(agency));
                }
            }
            String contact = SalesforceIds.normalize(validOrNull(Records.path(r, m.contactField())));
            if (contact != null) {
                accountIds.addAll(entitlementsByContact.getOrDefault(contact, Set.of()));
            }
            Set<String> dns = new LinkedHashSet<>();
            for (String accountId : accountIds) {
                String dn = accountDnBySfId.apply(accountId);
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

    private static String first(List<String> values) {
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private static String validOrNull(String id) {
        return SalesforceIds.isValid(id) ? id : null;
    }
}
