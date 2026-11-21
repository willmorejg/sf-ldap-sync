package com.acmeinsurance.directory.model;

import java.util.List;

/**
 * One attribute of a kind of entry, as the forms show it.
 *
 * @param dnKinds for DN fields, the kinds of entry a value may name
 */
public record FieldDef(String attribute, String label, FieldType type, boolean required, List<String> dnKinds,
                       String help) {

    public static FieldDef text(final String attribute, final String label) {
        return new FieldDef(attribute, label, FieldType.TEXT, false, List.of(), null);
    }

    public static FieldDef required(final String attribute, final String label) {
        return new FieldDef(attribute, label, FieldType.TEXT, true, List.of(), null);
    }

    public static FieldDef list(final String attribute, final String label, final String help) {
        return new FieldDef(attribute, label, FieldType.TEXT_LIST, false, List.of(), help);
    }

    public static FieldDef dn(final String attribute, final String label, final List<String> kinds,
                              final String help) {
        return new FieldDef(attribute, label, FieldType.DN, false, kinds, help);
    }

    public static FieldDef dnList(final String attribute, final String label, final List<String> kinds,
                                  final String help) {
        return new FieldDef(attribute, label, FieldType.DN_LIST, false, kinds, help);
    }

    public static FieldDef readOnly(final String attribute, final String label, final String help) {
        return new FieldDef(attribute, label, FieldType.READONLY, false, List.of(), help);
    }

    public FieldDef withHelp(final String text) {
        return new FieldDef(attribute, label, type, required, dnKinds, text);
    }

    public boolean multiValued() {
        return type == FieldType.TEXT_LIST || type == FieldType.DN_LIST;
    }

    public boolean isDn() {
        return type == FieldType.DN || type == FieldType.DN_LIST;
    }
}
