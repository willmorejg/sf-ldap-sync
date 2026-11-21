package com.acmeinsurance.directory.model;

/** How a form shows an attribute. */
public enum FieldType {
    /** One line of text. */
    TEXT,
    /** Several values, one per line (e.g. policy numbers). */
    TEXT_LIST,
    /** One DN, picked from entries of the field's dnKinds. */
    DN,
    /** Several DNs (grants), picked from entries of the field's dnKinds. */
    DN_LIST,
    /** Shown, never changed here (e.g. the Salesforce ID). */
    READONLY
}
