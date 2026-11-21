package com.acmeinsurance.sync.core;

import java.util.regex.Pattern;

/**
 * Salesforce record IDs. The 15-character form is case-sensitive; the 18-character
 * form adds a checksum suffix that makes it unique case-insensitively. The REST API
 * always returns 18-character IDs, and the directory stores only that form so a
 * record can never appear under two spellings.
 */
public final class SalesforceIds {

    private static final Pattern ID = Pattern.compile("[a-zA-Z0-9]{15}|[a-zA-Z0-9]{18}");
    private static final String SUFFIX_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ012345";

    private SalesforceIds() {
    }

    public static boolean isValid(final String id) {
        return id != null && ID.matcher(id).matches();
    }

    /** Returns the 18-character form of a 15- or 18-character ID. */
    public static String to18(final String id) {
        if (!isValid(id)) {
            throw new IllegalArgumentException("Not a Salesforce ID: " + id);
        }
        if (id.length() == 18) {
            return id;
        }
        final StringBuilder suffix = new StringBuilder(3);
        for (int chunk = 0; chunk < 3; chunk++) {
            int bits = 0;
            for (int i = 0; i < 5; i++) {
                final char c = id.charAt(chunk * 5 + i);
                if (c >= 'A' && c <= 'Z') {
                    bits |= 1 << i;
                }
            }
            suffix.append(SUFFIX_CHARS.charAt(bits));
        }
        return id + suffix;
    }

    /** Like {@link #to18} but returns null for null or blank input. */
    public static String normalize(final String id) {
        return id == null || id.isBlank() ? null : to18(id.trim());
    }
}
