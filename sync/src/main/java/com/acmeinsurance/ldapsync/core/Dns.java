package com.acmeinsurance.ldapsync.core;

/** Minimal DN string helpers that need no LDAP library (used by the planners). */
public final class Dns {

    private Dns() {
    }

    /** Escapes an attribute value for use in an RDN, per RFC 4514 section 2.4. */
    public static String escapeValue(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean leading = i == 0 && (c == ' ' || c == '#');
            boolean trailing = i == value.length() - 1 && c == ' ';
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

    public static String child(String attr, String value, String parentDn) {
        return attr + "=" + escapeValue(value) + "," + parentDn;
    }
}
