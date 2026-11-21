package com.acmeinsurance.directory.model;

import static com.acmeinsurance.common.Constants.ACME_ACCOUNT_DN;
import static com.acmeinsurance.common.Constants.ACME_FEATURE_DN;
import static com.acmeinsurance.common.Constants.ACME_POLICY_NUMBER;
import static com.acmeinsurance.common.Constants.ACME_SALESFORCE_ID;
import static com.acmeinsurance.common.Constants.ACME_UNDERWRITING_COMPANY_DN;
import static com.acmeinsurance.common.Constants.CN;
import static com.acmeinsurance.common.Constants.DEPARTMENT_NUMBER;
import static com.acmeinsurance.common.Constants.DESCRIPTION;
import static com.acmeinsurance.common.Constants.DISPLAY_NAME;
import static com.acmeinsurance.common.Constants.EMPLOYEE_NUMBER;
import static com.acmeinsurance.common.Constants.GIVEN_NAME;
import static com.acmeinsurance.common.Constants.KIND_AGENCY;
import static com.acmeinsurance.common.Constants.KIND_AGENT;
import static com.acmeinsurance.common.Constants.KIND_AGREEMENT;
import static com.acmeinsurance.common.Constants.KIND_BOOK;
import static com.acmeinsurance.common.Constants.KIND_CLIENT;
import static com.acmeinsurance.common.Constants.KIND_FEATURE;
import static com.acmeinsurance.common.Constants.KIND_INTERNAL;
import static com.acmeinsurance.common.Constants.KIND_MEMBER;
import static com.acmeinsurance.common.Constants.KIND_OTHER;
import static com.acmeinsurance.common.Constants.KIND_PERMISSION;
import static com.acmeinsurance.common.Constants.KIND_UNDERWRITING_COMPANY;
import static com.acmeinsurance.common.Constants.MAIL;
import static com.acmeinsurance.common.Constants.MOBILE;
import static com.acmeinsurance.common.Constants.OC_ACME_ACCOUNT_ACCESS;
import static com.acmeinsurance.common.Constants.OC_ACME_AGENCY_ACCOUNT;
import static com.acmeinsurance.common.Constants.OC_ACME_AGREEMENT_ACCOUNT;
import static com.acmeinsurance.common.Constants.OC_ACME_BOOK_ACCOUNT;
import static com.acmeinsurance.common.Constants.OC_ACME_CLIENT_ACCOUNT;
import static com.acmeinsurance.common.Constants.OC_ACME_FEATURE;
import static com.acmeinsurance.common.Constants.OC_ACME_FEATURE_ACCESS;
import static com.acmeinsurance.common.Constants.OC_ACME_MEMBER_ACCESS;
import static com.acmeinsurance.common.Constants.OC_ACME_PERMISSION;
import static com.acmeinsurance.common.Constants.OC_ACME_UNDERWRITING_COMPANY;
import static com.acmeinsurance.common.Constants.OC_ACME_USER;
import static com.acmeinsurance.common.Constants.OC_INET_ORG_PERSON;
import static com.acmeinsurance.common.Constants.RDN_ACCOUNTS;
import static com.acmeinsurance.common.Constants.RDN_AGENCIES;
import static com.acmeinsurance.common.Constants.RDN_AGENTS;
import static com.acmeinsurance.common.Constants.RDN_CLIENTS;
import static com.acmeinsurance.common.Constants.RDN_FEATURES;
import static com.acmeinsurance.common.Constants.RDN_INTERNAL;
import static com.acmeinsurance.common.Constants.RDN_MEMBERS;
import static com.acmeinsurance.common.Constants.RDN_OTHER;
import static com.acmeinsurance.common.Constants.RDN_PEOPLE;
import static com.acmeinsurance.common.Constants.RDN_POLICY_AGENTS;
import static com.acmeinsurance.common.Constants.RDN_POLICY_INTERNAL;
import static com.acmeinsurance.common.Constants.RDN_UNDERWRITING_COMPANIES;
import static com.acmeinsurance.common.Constants.SN;
import static com.acmeinsurance.common.Constants.TELEPHONE_NUMBER;
import static com.acmeinsurance.common.Constants.TITLE;
import static com.acmeinsurance.common.Constants.UID;
import static com.acmeinsurance.common.Constants.USER_TYPE_AGENT;
import static com.acmeinsurance.common.Constants.USER_TYPE_INTERNAL;
import static com.acmeinsurance.common.Constants.USER_TYPE_MEMBER;
import static com.acmeinsurance.common.Constants.USER_TYPE_OTHER;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.acmeinsurance.directory.DirectoryConfig;

import jakarta.inject.Singleton;

/**
 * The kinds of entry InsureDirectory manages, following
 * docs/LDAP_Schema_Definition.md: people by user type, the agency &gt; agreement &gt;
 * book tree and clients, and the reference data (underwriting companies, features
 * and permissions). The Angular forms are built from these definitions.
 */
@Singleton
public final class Kinds {

    private static final List<String> ACCOUNT_KINDS = List.of(KIND_AGENCY, KIND_AGREEMENT, KIND_BOOK, KIND_CLIENT);
    private static final List<String> FEATURE_KINDS = List.of(KIND_FEATURE, KIND_PERMISSION);
    private static final String PEOPLE = "People";
    private static final String ACCOUNTS = "Accounts";
    private static final String REFERENCE = "Reference data";
    private static final String SF_ID_HELP = "Set by the Salesforce sync";

    private final Map<String, KindDef> kinds = new LinkedHashMap<>();

    public Kinds(final DirectoryConfig cfg) {
        final FieldDef accounts = FieldDef.dnList(ACME_ACCOUNT_DN, "Account access", ACCOUNT_KINDS,
                "Agencies, agreements, books or clients this person may see");
        final FieldDef features = FieldDef.dnList(ACME_FEATURE_DN, "Features", FEATURE_KINDS,
                "A feature grants all of its permissions");
        final FieldDef policies = FieldDef.list(ACME_POLICY_NUMBER, "Policy numbers", "One policy number per line");
        final FieldDef title = FieldDef.text(TITLE, "Job title");

        person(KIND_MEMBER, "Member", "Members", "Insureds: they see their own policies", USER_TYPE_MEMBER,
                RDN_MEMBERS, null, List.of(OC_ACME_MEMBER_ACCESS), List.of(policies), Set.of());
        person(KIND_AGENT, "Agent", "Agents", "Brokers and agents, mostly maintained from Salesforce",
                USER_TYPE_AGENT, RDN_AGENTS, RDN_POLICY_AGENTS, List.of(OC_ACME_ACCOUNT_ACCESS, OC_ACME_FEATURE_ACCESS),
                List.of(title, accounts, features, FieldDef.readOnly(ACME_SALESFORCE_ID, "Salesforce ID",
                        SF_ID_HELP)),
                // The Salesforce sync never touches feature grants.
                Set.of(ACME_FEATURE_DN));
        person(KIND_INTERNAL, "Internal user", "Internal users", "Home office staff", USER_TYPE_INTERNAL,
                RDN_INTERNAL, RDN_POLICY_INTERNAL, List.of(OC_ACME_FEATURE_ACCESS, OC_ACME_ACCOUNT_ACCESS),
                List.of(title, FieldDef.text(EMPLOYEE_NUMBER, "Employee number"),
                        FieldDef.text(DEPARTMENT_NUMBER, "Department"), features, accounts), Set.of());
        person(KIND_OTHER, "Other user", "Other users", "Auditors, partners and anyone else", USER_TYPE_OTHER,
                RDN_OTHER, null, List.of(OC_ACME_FEATURE_ACCESS, OC_ACME_ACCOUNT_ACCESS, OC_ACME_MEMBER_ACCESS),
                List.of(title, features, accounts, policies), Set.of());

        final FieldDef sfId = FieldDef.readOnly(ACME_SALESFORCE_ID, "Salesforce ID", SF_ID_HELP);
        account(KIND_AGENCY, "Agency", "Agencies", "Brokers and MGAs: the top of the account tree",
                OC_ACME_AGENCY_ACCOUNT, RDN_AGENCIES + "," + RDN_ACCOUNTS, null, KIND_AGREEMENT, List.of(sfId),
                Set.of());
        account(KIND_AGREEMENT, "Agreement", "Agreements", "Contracts or product lines within an agency",
                OC_ACME_AGREEMENT_ACCOUNT, null, KIND_AGENCY, KIND_BOOK,
                List.of(FieldDef.dn(ACME_UNDERWRITING_COMPANY_DN, "Underwriting company",
                        List.of(KIND_UNDERWRITING_COMPANY), null), sfId),
                cfg.salesforceManagesUnderwritingCompany() ? Set.of() : Set.of(ACME_UNDERWRITING_COMPANY_DN));
        account(KIND_BOOK, "Book", "Books", "Portfolios of policies within an agreement", OC_ACME_BOOK_ACCOUNT,
                null, KIND_AGREEMENT, null, List.of(sfId), Set.of());
        account(KIND_CLIENT, "Client", "Clients", "Internally generated client IDs, outside the agency tree",
                OC_ACME_CLIENT_ACCOUNT, RDN_CLIENTS + "," + RDN_ACCOUNTS, null, null, List.of(), Set.of());

        reference(KIND_UNDERWRITING_COMPANY, "Underwriting company", "Underwriting companies",
                "The companies an agreement may name", OC_ACME_UNDERWRITING_COMPANY, RDN_UNDERWRITING_COMPANIES,
                null, null);
        reference(KIND_FEATURE, "Feature", "Features", "Application features; granting one grants its permissions",
                OC_ACME_FEATURE, RDN_FEATURES, null, KIND_PERMISSION);
        reference(KIND_PERMISSION, "Permission", "Permissions", "One permission within a feature",
                OC_ACME_PERMISSION, null, KIND_FEATURE, null);
    }

    public Collection<KindDef> all() {
        return kinds.values();
    }

    public Optional<KindDef> find(final String id) {
        return Optional.ofNullable(id == null ? null : kinds.get(id));
    }

    private void person(final String id, final String label, final String plural, final String description,
                        final String userType, final String rdn, final String policy, final List<String> access,
                        final List<FieldDef> extra, final Set<String> editableWhenSalesforce) {
        final List<FieldDef> fields = new ArrayList<>(List.of(
                FieldDef.required(UID, "User name").withHelp("Sign-in name; cannot be changed later"),
                FieldDef.text(GIVEN_NAME, "First name"),
                FieldDef.required(SN, "Last name"),
                FieldDef.text(CN, "Full name").withHelp("Left empty: first and last name"),
                FieldDef.text(DISPLAY_NAME, "Display name"),
                FieldDef.text(MAIL, "Email"),
                FieldDef.text(TELEPHONE_NUMBER, "Phone"),
                FieldDef.text(MOBILE, "Mobile")));
        fields.addAll(extra);
        final List<String> classes = new ArrayList<>(List.of(OC_INET_ORG_PERSON, OC_ACME_USER));
        classes.addAll(access);
        kinds.put(id, new KindDef(id, label, plural, PEOPLE, description, OC_INET_ORG_PERSON, List.copyOf(classes),
                UID, rdn + "," + RDN_PEOPLE, null, null, true, userType, policy, List.copyOf(fields),
                editableWhenSalesforce));
    }

    private void account(final String id, final String label, final String plural, final String description,
                         final String objectClass, final String baseRdn, final String parent, final String child,
                         final List<FieldDef> extra, final Set<String> editableWhenSalesforce) {
        final List<FieldDef> fields = new ArrayList<>(List.of(
                FieldDef.required(CN, "Account number").withHelp("Cannot be changed later"),
                FieldDef.text(DISPLAY_NAME, "Name"),
                FieldDef.text(DESCRIPTION, "Description")));
        fields.addAll(extra);
        kinds.put(id, new KindDef(id, label, plural, ACCOUNTS, description, objectClass, List.of(objectClass), CN,
                baseRdn, parent, child, true, null, null, List.copyOf(fields), editableWhenSalesforce));
    }

    private void reference(final String id, final String label, final String plural, final String description,
                           final String objectClass, final String baseRdn, final String parent, final String child) {
        final List<FieldDef> fields = List.of(
                FieldDef.required(CN, "Name").withHelp("Cannot be changed later"),
                FieldDef.text(DISPLAY_NAME, "Display name"),
                FieldDef.text(DESCRIPTION, "Description"));
        kinds.put(id, new KindDef(id, label, plural, REFERENCE, description, objectClass, List.of(objectClass), CN,
                baseRdn, parent, child, false, null, null, fields, Set.of()));
    }
}
