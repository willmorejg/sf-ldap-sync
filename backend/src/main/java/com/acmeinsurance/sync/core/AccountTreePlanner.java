package com.acmeinsurance.sync.core;

import static com.acmeinsurance.common.Constants.CN;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Works out where each agency, agreement and book belongs in the directory tree
 * (agency &gt; agreement &gt; book under ou=agencies) and rejects records that
 * cannot be placed. Pure logic: no LDAP access.
 */
public final class AccountTreePlanner {
    private static final String PARENT = "parent ";

    /** A record that has a place in the tree. */
    public record Planned(AccountNode node, String dn, String parentDn) {
    }

    public record Rejected(AccountNode node, String reason) {
    }

    /**
     * @param ordered  placeable records, parents always before their children
     * @param rejected records that could not be placed, with the reason
     * @param seenIds  every Salesforce ID in the input, placed or not; the sync
     *                 never removes a directory entry whose ID is in this set
     */
    public record Plan(List<Planned> ordered, List<Rejected> rejected, Set<String> seenIds) {
    }

    private AccountTreePlanner() {
    }

    public static Plan plan(final Collection<AccountNode> nodes, final String agenciesDn) {
        final Map<String, AccountNode> byId = new LinkedHashMap<>();
        final List<Rejected> rejected = new ArrayList<>();
        final Set<String> seen = new HashSet<>();
        for (final AccountNode n : nodes) {
            seen.add(n.sfId());
            if (byId.putIfAbsent(n.sfId(), n) != null) {
                rejected.add(new Rejected(n, "duplicate Salesforce ID in the query result"));
            }
        }

        final Map<String, String> dnById = new HashMap<>();
        final Set<String> usedDns = new HashSet<>();
        final List<Planned> ordered = new ArrayList<>();
        final Comparator<AccountNode> byCnThenId =
                Comparator.comparing(AccountNode::cn).thenComparing(AccountNode::sfId);

        for (final AccountLevel level : AccountLevel.values()) {
            final List<AccountNode> atLevel = byId.values().stream()
                    .filter(n -> n.level() == level)
                    .sorted(byCnThenId)
                    .toList();
            for (final AccountNode n : atLevel) {
                String parentDn;
                if (level.parent() == null) {
                    parentDn = agenciesDn;
                } else {
                    if (n.parentSfId() == null) {
                        rejected.add(new Rejected(n, level.label() + " has no parent " + level.parent().label()));
                        continue;
                    }
                    final AccountNode parent = byId.get(n.parentSfId());
                    if (parent == null) {
                        rejected.add(new Rejected(n, PARENT + n.parentSfId() + " is not in the query result"));
                        continue;
                    }
                    if (parent.level() != level.parent()) {
                        rejected.add(new Rejected(n, PARENT + parent.sfId() + " is a " + parent.level().label()
                                + "; a " + level.label() + " must sit under a " + level.parent().label()));
                        continue;
                    }
                    parentDn = dnById.get(parent.sfId());
                    if (parentDn == null) {
                        rejected.add(new Rejected(n, PARENT + parent.sfId() + " was itself rejected"));
                        continue;
                    }
                }
                final String dn = Dns.child(CN, n.cn(), parentDn);
                if (!usedDns.add(dn.toLowerCase(Locale.ROOT))) {
                    rejected.add(new Rejected(n, "cn '" + n.cn() + "' is already used by another record under the same parent"));
                    continue;
                }
                dnById.put(n.sfId(), dn);
                ordered.add(new Planned(n, dn, parentDn));
            }
        }
        return new Plan(ordered, rejected, seen);
    }
}
