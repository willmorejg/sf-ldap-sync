package com.acmeinsurance.ldapsync.core;

import java.util.Map;

/** Reads fields from Salesforce query records parsed into nested maps. */
public final class Records {

    private Records() {
    }

    /**
     * Returns the value at a dotted path such as {@code Contact.AccountId} or
     * {@code RecordType.DeveloperName}, as a trimmed string, or null when any part
     * of the path is missing, null or blank.
     */
    public static String path(Map<String, Object> record, String dottedPath) {
        if (record == null || dottedPath == null || dottedPath.isBlank()) {
            return null;
        }
        Object current = record;
        for (String part : dottedPath.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(part);
            if (current == null) {
                return null;
            }
        }
        if (current instanceof Map<?, ?>) {
            return null;
        }
        String s = String.valueOf(current).trim();
        return s.isEmpty() ? null : s;
    }

    /** Interprets a Salesforce boolean field; missing means {@code defaultValue}. */
    public static boolean bool(Map<String, Object> record, String dottedPath, boolean defaultValue) {
        String v = path(record, dottedPath);
        return v == null ? defaultValue : Boolean.parseBoolean(v);
    }
}
