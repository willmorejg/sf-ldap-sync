package com.acmeinsurance.ldapsync.core;

/**
 * Names shared across the sync: LDAP attributes and object classes (see
 * {@code openldap/ldif/0*-acmeinsurance-*.ldif}), the values of {@code acmeSource}
 * and {@code acmeUserType}, and the CSV feed's folders, columns and reference
 * prefixes. Use these instead of string literals, so a rename happens in one place.
 *
 * <p>An enum with a single {@code INSTANCE} value: it cannot be instantiated or
 * subclassed, and holds only constants.
 */
public enum Constants {
    INSTANCE;

    // ---- LDAP attributes: standard (core, cosine, inetOrgPerson) -----------

    public static final String OBJECT_CLASS = "objectClass";
    public static final String CN = "cn";
    public static final String SN = "sn";
    public static final String UID = "uid";
    public static final String GIVEN_NAME = "givenName";
    public static final String DISPLAY_NAME = "displayName";
    public static final String MAIL = "mail";
    public static final String TELEPHONE_NUMBER = "telephoneNumber";
    public static final String MOBILE = "mobile";
    public static final String TITLE = "title";
    public static final String EMPLOYEE_NUMBER = "employeeNumber";
    public static final String DEPARTMENT_NUMBER = "departmentNumber";

    // ---- LDAP attributes: Acme Insurance schema ----------------------------

    public static final String ACME_USER_TYPE = "acmeUserType";
    public static final String ACME_ACCOUNT_DN = "acmeAccountDN";
    public static final String ACME_FEATURE_DN = "acmeFeatureDN";
    public static final String ACME_POLICY_NUMBER = "acmePolicyNumber";
    public static final String ACME_SALESFORCE_ID = "acmeSalesforceId";
    public static final String ACME_SOURCE = "acmeSource";
    public static final String ACME_DEACTIVATED = "acmeDeactivated";

    // ---- LDAP attributes: password policy (ppolicy overlay) ----------------

    public static final String PWD_ACCOUNT_LOCKED_TIME = "pwdAccountLockedTime";
    public static final String PWD_POLICY_SUBENTRY = "pwdPolicySubentry";
    /** pwdAccountLockedTime value that locks an account until it is removed. */
    public static final String PERMANENT_LOCK = "000001010000Z";

    // ---- Object classes ----------------------------------------------------

    public static final String OC_INET_ORG_PERSON = "inetOrgPerson";
    public static final String OC_ACME_USER = "acmeUser";
    public static final String OC_ACME_ACCOUNT_ACCESS = "acmeAccountAccess";
    public static final String OC_ACME_FEATURE_ACCESS = "acmeFeatureAccess";
    public static final String OC_ACME_MEMBER_ACCESS = "acmeMemberAccess";
    public static final String OC_ACME_SYNC_MANAGED = "acmeSyncManaged";
    public static final String OC_ACME_AGENCY_ACCOUNT = "acmeAgencyAccount";
    public static final String OC_ACME_AGREEMENT_ACCOUNT = "acmeAgreementAccount";
    public static final String OC_ACME_BOOK_ACCOUNT = "acmeBookAccount";

    // ---- acmeSource values (who owns an entry) -----------------------------

    public static final String SOURCE_SALESFORCE = "salesforce";
    public static final String SOURCE_CSV = "csv";
    /** Hand-made entries; treated exactly like entries with no acmeSource. */
    public static final String SOURCE_MANUAL = "manual";
    /** Older CSV feeds wrote csv-internal, csv-members and csv-other; "csv" owns those too. */
    public static final String SOURCE_CSV_LEGACY_PREFIX = "csv-";

    // ---- acmeUserType values -----------------------------------------------

    public static final String USER_TYPE_AGENT = "agent";
    public static final String USER_TYPE_INTERNAL = "internal";
    public static final String USER_TYPE_MEMBER = "member";
    public static final String USER_TYPE_OTHER = "other";

    // ---- CSV feed ----------------------------------------------------------

    /** Inbox folders; the folder decides the user type. */
    public static final String CSV_FOLDER_INTERNAL = "internal";
    public static final String CSV_FOLDER_MEMBERS = "members";
    public static final String CSV_FOLDER_OTHER = "other";

    /** CSV columns that are not LDAP attribute names. */
    public static final String CSV_COLUMN_ACTIVE = "active";
    public static final String CSV_COLUMN_ACCOUNTS = "accounts";
    public static final String CSV_COLUMN_POLICY_NUMBERS = "policyNumbers";
    public static final String CSV_COLUMN_FEATURES = "features";

    /** Account reference prefixes in the accounts column: sf:&lt;Salesforce ID&gt;, client:&lt;client ID&gt;. */
    public static final String REF_SALESFORCE_PREFIX = "sf:";
    public static final String REF_CLIENT_PREFIX = "client:";

    // ---- Misc --------------------------------------------------------------

    /** LDAP URL scheme for TLS connections. */
    public static final String LDAPS_SCHEME = "ldaps";
}
