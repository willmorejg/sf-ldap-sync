package com.acmeinsurance.ldapsync.core;

/** Minimal DN string helpers that need no LDAP library (used by the planners). */
public final class Dns {

    private Dns() {
    }

    /** Escapes an attribute value for use in an RDN, per RFC 4514 section 2.4. */
    public static String escapeValue(final String value) {
        final StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            final boolean leading = i == 0 && (c == ' ' || c == '#');
            final boolean trailing = i == value.length() - 1 && c == ' ';
            if (leading || trailing || ",+\"\\<>;=".indexOf(c) >= 0) {
                sb.append('\\').append(c);
            } else if (c == '\0') {
                sb.append("\\00");
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public static String child(final String attr, final String value, final String parentDn) {
        return attr + "=" + escapeValue(value) + "," + parentDn;
    }
}
