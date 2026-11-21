package com.acmeinsurance.common.ldap;

import java.util.Locale;

import com.unboundid.ldap.sdk.DN;
import com.unboundid.ldap.sdk.LDAPException;

/** DN comparisons used by every module. Invalid DNs are never in scope and never equal. */
public final class DnUtil {

    private DnUtil() {
    }

    /** True when {@code dn} is strictly below {@code baseDn}. */
    public static boolean isUnder(final String dn, final String baseDn) {
        try {
            return new DN(dn).isDescendantOf(new DN(baseDn), false);
        } catch (final LDAPException e) {
            return false;
        }
    }

    /** True when {@code dn} is {@code baseDn} or below it. */
    public static boolean isWithin(final String dn, final String baseDn) {
        try {
            return new DN(dn).isDescendantOf(new DN(baseDn), true);
        } catch (final LDAPException e) {
            return false;
        }
    }

    /** Normalized form for comparing DNs; falls back to lower case for invalid input. */
    public static String normalize(final String dn) {
        try {
            return new DN(dn).toNormalizedString();
        } catch (final LDAPException e) {
            return dn.toLowerCase(Locale.ROOT);
        }
    }

    public static boolean sameDn(final String a, final String b) {
        return normalize(a).equals(normalize(b));
    }

    /** Number of RDNs; used to delete children before parents. */
    public static int depth(final String dn) {
        try {
            return new DN(dn).getRDNs().length;
        } catch (final LDAPException e) {
            return 0;
        }
    }
}
