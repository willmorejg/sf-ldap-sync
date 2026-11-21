package com.acmeinsurance.sync.core;

import java.util.Optional;

/**
 * Stops a sync from mass-removing entries because of a bad query, a permissions
 * change in Salesforce or an outage that returns an empty result.
 */
public final class SafetyGuard {

    private SafetyGuard() {
    }

    /**
     * @param sourceRows rows the source returned this run
     * @param owned      directory entries currently owned by the source
     * @param toRemove   entries the sync wants to remove or deactivate
     * @param maxPercent largest share of owned entries one run may remove
     * @return a reason to refuse, or empty when the removal may go ahead
     */
    public static Optional<String> checkRemoval(final int sourceRows, final int owned, final int toRemove,
                                                final int maxPercent) {
        if (toRemove == 0) {
            return Optional.empty();
        }
        if (sourceRows == 0) {
            return Optional.of("the source returned no rows; refusing to remove " + toRemove + " entries");
        }
        if (owned > 0 && (long) toRemove * 100 > (long) maxPercent * owned) {
            return Optional.of("would remove " + toRemove + " of " + owned + " entries ("
                    + (toRemove * 100 / owned) + "%), above the " + maxPercent
                    + "% limit (sync.safety.max-remove-percent)");
        }
        return Optional.empty();
    }
}
