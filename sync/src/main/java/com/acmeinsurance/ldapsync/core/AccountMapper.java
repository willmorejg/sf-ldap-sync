package com.acmeinsurance.ldapsync.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Turns Salesforce Account query records into {@link AccountNode}s. */
public final class AccountMapper {

    /**
     * Field names (dotted paths allowed) used to read each record.
     *
     * @param recordTypes record-type value (e.g. "Agency") to level; records whose
     *                    type is not listed are skipped
     */
    public record Mapping(String idField, String parentField, String levelField, String cnField,
                          String nameField, Map<String, AccountLevel> recordTypes) {
    }

    public record Result(List<AccountNode> nodes, int skipped, List<String> warnings) {
    }

    private AccountMapper() {
    }

    public static Result map(final List<Map<String, Object>> records, final Mapping m) {
        final List<AccountNode> nodes = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        int skipped = 0;
        for (final Map<String, Object> r : records) {
            final String rawId = Records.path(r, m.idField());
            if (!SalesforceIds.isValid(rawId)) {
                warnings.add("Skipped account record without a valid " + m.idField() + ": " + rawId);
                skipped++;
                continue;
            }
            final String id = SalesforceIds.to18(rawId);
            final String type = Records.path(r, m.levelField());
            final AccountLevel level = type == null ? null : m.recordTypes().get(type);
            if (level == null) {
                skipped++;
                continue;
            }
            String parent = Records.path(r, m.parentField());
            if (parent != null && !SalesforceIds.isValid(parent)) {
                warnings.add(id + ": ignoring invalid parent ID " + parent);
                parent = null;
            }
            String cn = Records.path(r, m.cnField());
            if (cn == null) {
                warnings.add(id + ": " + m.cnField() + " is empty; using the Salesforce ID as cn");
                cn = id;
            }
            nodes.add(new AccountNode(id, level, cn, SalesforceIds.normalize(parent), Records.path(r, m.nameField())));
        }
        return new Result(nodes, skipped, warnings);
    }
}
