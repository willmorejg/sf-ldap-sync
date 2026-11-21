package com.acmeinsurance.directory.model;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * One kind of directory entry the app manages: where it lives, its object
 * classes, and the fields its form shows.
 *
 * @param group                  menu group: People, Accounts or Reference data
 * @param structuralClass        the object class that identifies the kind
 * @param objectClasses          every class a new entry gets
 * @param baseRdn                where entries live, below the base DN; null when they live under a parent entry
 * @param parentKind             kind of the parent entry (agreement: agency); null for top-level kinds
 * @param childKind              kind of the entries below (agency: agreement); null for leaves
 * @param sourceTracked          new entries get acmeSyncManaged with acmeSource=manual
 * @param userType               acmeUserType of new people; null for other kinds
 * @param passwordPolicy         pwdPolicySubentry RDN (below ou=policies) of new people; null: the default policy
 * @param editableWhenSalesforce attributes that may still be changed on entries the Salesforce sync owns
 */
public record KindDef(String id, String label, String plural, String group, String description,
                      String structuralClass, List<String> objectClasses, String rdnAttribute, String baseRdn,
                      String parentKind, String childKind, boolean sourceTracked, String userType,
                      String passwordPolicy, List<FieldDef> fields, Set<String> editableWhenSalesforce) {

    public boolean person() {
        return userType != null;
    }

    public Optional<FieldDef> field(final String attribute) {
        return fields.stream().filter(f -> f.attribute().equalsIgnoreCase(attribute)).findFirst();
    }
}
