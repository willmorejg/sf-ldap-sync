package com.acmeinsurance.ldapsync.core;

import java.util.Locale;

/** The three Salesforce account levels, in parent-to-child order. */
public enum AccountLevel {
    AGENCY("acmeAgencyAccount", null),
    AGREEMENT("acmeAgreementAccount", AGENCY),
    BOOK("acmeBookAccount", AGREEMENT);

    private final String objectClass;
    private final AccountLevel parent;

    AccountLevel(final String objectClass, final AccountLevel parent) {
        this.objectClass = objectClass;
        this.parent = parent;
    }

    /** Structural LDAP object class for entries at this level. */
    public String objectClass() {
        return objectClass;
    }

    /** Level an entry's parent must be at; null for agencies. */
    public AccountLevel parent() {
        return parent;
    }

    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Parses "agency", "agreement" or "book" (any case). */
    public static AccountLevel parse(final String s) {
        return valueOf(s.trim().toUpperCase(Locale.ROOT));
    }
}
