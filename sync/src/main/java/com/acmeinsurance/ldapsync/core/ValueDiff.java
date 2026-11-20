package com.acmeinsurance.ldapsync.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Value-level difference for a multi-valued attribute such as acmeAccountDN.
 *
 * <p>Only values inside the source's scope are ever removed. For example, the
 * Salesforce sync manages grants under ou=agencies but must leave an agent's
 * grants to internal client IDs (ou=clients) alone.
 */
public final class ValueDiff {

    public record Diff(List<String> add, List<String> remove) {
        public boolean isEmpty() {
            return add.isEmpty() && remove.isEmpty();
        }
    }

    private ValueDiff() {
    }

    /**
     * @param existing   values on the entry now
     * @param desired    values the source wants (all should be inside the scope)
     * @param inScope    which existing values this source manages
     * @param prune      remove in-scope values the source no longer lists
     * @param normalizer comparison key (e.g. DN normalization or lower-casing)
     */
    public static Diff diff(Collection<String> existing, Collection<String> desired,
                            Predicate<String> inScope, boolean prune, Function<String, String> normalizer) {
        Map<String, String> have = index(existing, normalizer);
        Map<String, String> want = index(desired, normalizer);
        List<String> add = new ArrayList<>();
        want.forEach((key, value) -> {
            if (!have.containsKey(key)) {
                add.add(value);
            }
        });
        List<String> remove = new ArrayList<>();
        if (prune) {
            have.forEach((key, value) -> {
                if (!want.containsKey(key) && inScope.test(value)) {
                    remove.add(value);
                }
            });
        }
        return new Diff(add, remove);
    }

    private static Map<String, String> index(Collection<String> values, Function<String, String> normalizer) {
        Map<String, String> m = new LinkedHashMap<>();
        if (values != null) {
            for (String v : values) {
                m.putIfAbsent(normalizer.apply(v), v);
            }
        }
        return m;
    }
}
