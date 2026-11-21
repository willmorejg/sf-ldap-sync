package com.acmeinsurance.common;

/**
 * Names used across the backend: LDAP attributes, object classes, values and tree
 * names (see {@code openldap/ldif}), the OAuth 2.0 and JWT vocabulary (RFC 6749,
 * RFC 7636, RFC 9068), the features and permissions the backend checks, HTTP
 * names, and the sync's CSV feed vocabulary. Use these instead of string
 * literals, so a rename happens in one place.
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
    public static final String DESCRIPTION = "description";
    public static final String USER_PASSWORD = "userPassword";

    // ---- LDAP attributes: operational ----------------------------------------

    public static final String ENTRY_UUID = "entryUUID";
    public static final String CREATE_TIMESTAMP = "createTimestamp";
    public static final String MODIFY_TIMESTAMP = "modifyTimestamp";
    public static final String MODIFIERS_NAME = "modifiersName";
    /** Search attribute list entries: all user attributes, all operational attributes. */
    public static final String ALL_USER_ATTRIBUTES = "*";
    public static final String ALL_OPERATIONAL_ATTRIBUTES = "+";

    // ---- LDAP attributes: Acme Insurance schema ----------------------------

    public static final String ACME_USER_TYPE = "acmeUserType";
    public static final String ACME_ACCOUNT_DN = "acmeAccountDN";
    public static final String ACME_FEATURE_DN = "acmeFeatureDN";
    public static final String ACME_POLICY_NUMBER = "acmePolicyNumber";
    public static final String ACME_SALESFORCE_ID = "acmeSalesforceId";
    public static final String ACME_SOURCE = "acmeSource";
    public static final String ACME_DEACTIVATED = "acmeDeactivated";
    /** On agreements: DN of the underwriting company, an entry under ou=underwritingCompanies. */
    public static final String ACME_UNDERWRITING_COMPANY_DN = "acmeUnderwritingCompanyDN";
    public static final String ACME_REDIRECT_URI = "acmeRedirectUri";
    public static final String ACME_GRANT_TYPE = "acmeGrantType";

    // ---- LDAP attributes: password policy (ppolicy overlay) ----------------

    public static final String PWD_ACCOUNT_LOCKED_TIME = "pwdAccountLockedTime";
    public static final String PWD_FAILURE_TIME = "pwdFailureTime";
    public static final String PWD_POLICY_SUBENTRY = "pwdPolicySubentry";
    public static final String PWD_CHANGED_TIME = "pwdChangedTime";
    public static final String PWD_RESET = "pwdReset";
    /** pwdAccountLockedTime value that locks an account until it is removed. */
    public static final String PERMANENT_LOCK = "000001010000Z";
    /** LDAP Boolean true. */
    public static final String LDAP_TRUE = "TRUE";

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
    public static final String OC_ACME_CLIENT_ACCOUNT = "acmeClientAccount";
    public static final String OC_ACME_UNDERWRITING_COMPANY = "acmeUnderwritingCompany";
    public static final String OC_ACME_FEATURE = "acmeFeature";
    public static final String OC_ACME_PERMISSION = "acmePermission";
    public static final String OC_ACME_OAUTH_CLIENT = "acmeOAuthClient";

    // ---- Directory tree (RDNs below the base DN) ---------------------------

    public static final String RDN_PEOPLE = "ou=people";
    public static final String RDN_FEATURES = "ou=features";
    public static final String RDN_ACCOUNTS = "ou=accounts";
    public static final String RDN_AGENCIES = "ou=agencies";
    public static final String RDN_CLIENTS = "ou=clients";
    public static final String RDN_SERVICES = "ou=services";
    public static final String RDN_OAUTH_CLIENTS = "ou=oauth-clients";
    public static final String RDN_POLICIES = "ou=policies";
    public static final String RDN_UNDERWRITING_COMPANIES = "ou=underwritingCompanies";
    /** People, one container per user type (below ou=people). */
    public static final String RDN_AGENTS = "ou=agents";
    public static final String RDN_MEMBERS = "ou=members";
    public static final String RDN_INTERNAL = "ou=internal";
    public static final String RDN_OTHER = "ou=other";
    /** Password policies (below ou=policies). */
    public static final String RDN_POLICY_AGENTS = "cn=agents";
    public static final String RDN_POLICY_INTERNAL = "cn=internal";

    /** LDAP URL scheme for TLS connections. */
    public static final String LDAPS_SCHEME = "ldaps";

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

    // ---- Features and permissions the backend checks (keys of the "features" claim) ----

    /** InsureDirectory (36-insure-directory.ldif). */
    public static final String FEATURE_DIRECTORY = "directory";
    public static final String PERMISSION_VIEW = "view-entries";
    public static final String PERMISSION_EDIT = "edit-entries";
    public static final String PERMISSION_QUERY = "run-queries";
    /** The sync's HTTP trigger (38-sync-feature.ldif). */
    public static final String FEATURE_SYNC = "sync";
    public static final String PERMISSION_RUN_SYNC = "run-sync";

    // ---- OAuth 2.0: grant and response types -------------------------------

    public static final String GRANT_TYPE_AUTHORIZATION_CODE = "authorization_code";
    public static final String GRANT_TYPE_PASSWORD = "password";
    public static final String GRANT_TYPE_REFRESH_TOKEN = "refresh_token";
    public static final String RESPONSE_TYPE_CODE = "code";
    /** The only PKCE code_challenge_method accepted. */
    public static final String PKCE_METHOD_S256 = "S256";
    public static final String TOKEN_TYPE_BEARER = "Bearer";

    // ---- OAuth 2.0: parameters (requests, redirects and token responses) ---

    public static final String PARAM_RESPONSE_TYPE = "response_type";
    public static final String PARAM_CLIENT_ID = "client_id";
    public static final String PARAM_CLIENT_SECRET = "client_secret";
    public static final String PARAM_REDIRECT_URI = "redirect_uri";
    public static final String PARAM_STATE = "state";
    public static final String PARAM_CODE = "code";
    public static final String PARAM_CODE_CHALLENGE = "code_challenge";
    public static final String PARAM_CODE_CHALLENGE_METHOD = "code_challenge_method";
    public static final String PARAM_CODE_VERIFIER = "code_verifier";
    public static final String PARAM_GRANT_TYPE = "grant_type";
    public static final String PARAM_USERNAME = "username";
    public static final String PARAM_PASSWORD = "password";
    public static final String PARAM_REFRESH_TOKEN = "refresh_token";
    public static final String PARAM_ACCESS_TOKEN = "access_token";
    public static final String PARAM_TOKEN_TYPE = "token_type";
    public static final String PARAM_EXPIRES_IN = "expires_in";
    /** Not in RFC 6749; seconds until the refresh token expires. */
    public static final String PARAM_REFRESH_EXPIRES_IN = "refresh_expires_in";
    public static final String PARAM_ERROR = "error";
    public static final String PARAM_ERROR_DESCRIPTION = "error_description";

    // ---- OAuth 2.0: error codes --------------------------------------------

    public static final String ERROR_INVALID_REQUEST = "invalid_request";
    public static final String ERROR_INVALID_CLIENT = "invalid_client";
    public static final String ERROR_INVALID_GRANT = "invalid_grant";
    public static final String ERROR_UNAUTHORIZED_CLIENT = "unauthorized_client";
    public static final String ERROR_UNSUPPORTED_GRANT_TYPE = "unsupported_grant_type";
    public static final String ERROR_UNSUPPORTED_RESPONSE_TYPE = "unsupported_response_type";
    public static final String ERROR_TEMPORARILY_UNAVAILABLE = "temporarily_unavailable";
    /** RFC 6750: the bearer token is missing, expired or not valid. */
    public static final String ERROR_INVALID_TOKEN = "invalid_token";
    /** RFC 6750: the token lacks the permission the request needs. */
    public static final String ERROR_INSUFFICIENT_SCOPE = "insufficient_scope";

    // ---- JWT -----------------------------------------------------------------

    /** "typ" header of access tokens (RFC 9068). */
    public static final String TYP_ACCESS_TOKEN = "at+jwt";
    /** "typ" header of refresh tokens. */
    public static final String TYP_REFRESH_TOKEN = "rt+jwt";

    public static final String CLAIM_SUBJECT = "sub";
    public static final String CLAIM_CLIENT_ID = "client_id";
    public static final String CLAIM_AUTH_TIME = "auth_time";
    public static final String CLAIM_PREFERRED_USERNAME = "preferred_username";
    public static final String CLAIM_NAME = "name";
    public static final String CLAIM_GIVEN_NAME = "given_name";
    public static final String CLAIM_FAMILY_NAME = "family_name";
    public static final String CLAIM_EMAIL = "email";
    public static final String CLAIM_USER_TYPE = "user_type";
    public static final String CLAIM_FEATURES = "features";
    public static final String CLAIM_ACCOUNTS = "accounts";
    public static final String CLAIM_POLICIES = "policies";
    /** Keys of the accounts claim. */
    public static final String CLAIM_ACCOUNTS_AGENCIES = "agencies";
    public static final String CLAIM_ACCOUNTS_AGREEMENTS = "agreements";
    public static final String CLAIM_ACCOUNTS_BOOKS = "books";
    public static final String CLAIM_ACCOUNTS_CLIENTS = "clients";
    /** Agreement path to the cn of its underwriting company. */
    public static final String CLAIM_ACCOUNTS_UNDERWRITING_COMPANIES = "underwriting_companies";

    // ---- HTTP ------------------------------------------------------------------

    public static final String HEADER_AUTHORIZATION = "Authorization";
    public static final String HEADER_WWW_AUTHENTICATE = "WWW-Authenticate";
    public static final String HEADER_CONTENT_TYPE = "Content-Type";
    public static final String HEADER_CACHE_CONTROL = "Cache-Control";
    public static final String HEADER_PRAGMA = "Pragma";
    public static final String HEADER_LOCATION = "Location";
    public static final String HEADER_COOKIE = "Cookie";
    public static final String HEADER_SET_COOKIE = "Set-Cookie";
    public static final String HEADER_CSP = "Content-Security-Policy";
    public static final String HEADER_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options";
    public static final String HEADER_FRAME_OPTIONS = "X-Frame-Options";
    public static final String HEADER_REFERRER_POLICY = "Referrer-Policy";
    /** Sent by the Angular app on every request; required on changes (CSRF). */
    public static final String HEADER_REQUESTED_WITH = "X-Requested-With";

    public static final String MEDIA_TYPE_JSON = "application/json";
    public static final String MEDIA_TYPE_JWK_SET = "application/jwk-set+json";
    public static final String PRAGMA_NO_CACHE = "no-cache";
    public static final String CACHE_NO_STORE = "no-store";
    public static final String AUTH_SCHEME_BASIC = "Basic";
    /** realm of the WWW-Authenticate challenges (Basic for OAuth clients, Bearer for the sync trigger). */
    public static final String AUTH_REALM = "acmeinsurance";
    public static final String HTTP_GET = "GET";
    public static final String SCHEME_HTTPS = "https:";

    /** JSON error body of the backend's own endpoints: {"error": ..., "message": ...}. */
    public static final String JSON_ERROR = "error";
    public static final String JSON_MESSAGE = "message";

    // ---- Paths -------------------------------------------------------------------

    /** Token service endpoints. */
    public static final String PATH_OAUTH2 = "/oauth2";
    public static final String PATH_AUTHORIZE = "/oauth2/authorize";
    public static final String PATH_TOKEN = "/oauth2/token";
    public static final String PATH_JWKS = "/oauth2/jwks";
    public static final String PATH_WELL_KNOWN = "/.well-known";
    /** RFC 8414 authorization server metadata. */
    public static final String PATH_SERVER_METADATA = PATH_WELL_KNOWN + "/oauth-authorization-server";
    /** InsureDirectory: its API, its sign-in endpoints and the page shown when signed out. */
    public static final String PATH_API = "/api";
    public static final String PATH_APP_AUTH = "/auth";
    public static final String PATH_APP_CALLBACK = "/auth/callback";
    public static final String PATH_SIGNED_OUT = "/signed-out";
    /** The sync's HTTP trigger. */
    public static final String PATH_SYNC = "/sync";
    /** Quarkus endpoints (/q/openapi, /q/swagger-ui) and the Camel OpenAPI document. */
    public static final String PATH_QUARKUS = "/q";
    public static final String PATH_OPENAPI = "/openapi";
    public static final String PATH_INDEX_HTML = "/index.html";

    // ---- InsureDirectory: sessions and the Camel exchange ------------------------

    public static final String SESSION_COOKIE = "idir_session";
    public static final String REQUESTED_WITH_VALUE = "InsureDirectory";
    /** Query parameter of /auth/login: the local path to return to after sign-in. */
    public static final String PARAM_RETURN_TO = "returnTo";
    /** Exchange property holding the signed-in {@code directory.auth.Session}. */
    public static final String PROPERTY_SESSION = "directorySession";

    // ---- InsureDirectory: request parameters (Camel headers) and JSON fields -----

    /** Path and query parameters of the API, which the REST DSL puts in headers. */
    public static final String PARAM_KIND = "kind";
    public static final String PARAM_ID = "id";
    public static final String PARAM_PARENT = "parent";
    public static final String PARAM_KINDS = "kinds";
    public static final String PARAM_Q = "q";
    public static final String PARAM_LIMIT = "limit";
    /** Fields of the API's request and response bodies. */
    public static final String JSON_ID = "id";
    public static final String JSON_DN = "dn";
    public static final String JSON_KIND = "kind";
    public static final String JSON_KIND_LABEL = "kindLabel";
    public static final String JSON_RDN = "rdn";
    public static final String JSON_LABEL = "label";
    public static final String JSON_LABELS = "labels";
    public static final String JSON_PARENT_ID = "parentId";
    public static final String JSON_PARENT_LABEL = "parentLabel";
    public static final String JSON_DISPLAY_NAME = "displayName";
    public static final String JSON_MANAGED_BY = "managedBy";
    public static final String JSON_LOCKED = "locked";
    public static final String JSON_LOCKED_UNTIL_UNLOCKED = "lockedUntilUnlocked";
    public static final String JSON_PASSWORD_RESET = "passwordReset";
    public static final String JSON_PASSWORD_CHANGED = "passwordChanged";
    public static final String JSON_DEACTIVATED = "deactivated";
    public static final String JSON_UNDERWRITING_COMPANY = "underwritingCompany";
    public static final String JSON_EDITABLE = "editable";
    public static final String JSON_DELETABLE = "deletable";
    public static final String JSON_ATTRIBUTES = "attributes";
    public static final String JSON_STATUS = "status";
    public static final String JSON_META = "meta";
    public static final String JSON_CREATED = "created";
    public static final String JSON_MODIFIED = "modified";
    public static final String JSON_MODIFIED_BY = "modifiedBy";
    public static final String JSON_TRUNCATED = "truncated";
    public static final String JSON_LIMIT = "limit";
    public static final String JSON_ITEMS = "items";
    public static final String JSON_ENTRIES = "entries";
    public static final String JSON_ENTRY = "entry";
    public static final String JSON_TEMPORARY_PASSWORD = "temporaryPassword";
    public static final String JSON_DELETED = "deleted";
    public static final String JSON_COUNT = "count";
    public static final String JSON_SIZE_LIMIT = "sizeLimit";
    public static final String JSON_MILLIS = "millis";
    public static final String JSON_NAME = "name";
    public static final String JSON_DESCRIPTION = "description";
    public static final String JSON_SINGLE_VALUED = "singleValued";
    public static final String JSON_OBJECT_CLASSES = "objectClasses";
    public static final String JSON_SCOPE = "scope";
    public static final String JSON_FILTER = "filter";
    public static final String JSON_BASE_DN = "baseDn";
    public static final String JSON_USERNAME = "username";
    public static final String JSON_PERMISSIONS = "permissions";
    public static final String JSON_SIGNED_OUT = "signedOut";
    public static final String JSON_CN = "cn";
    public static final String JSON_MAIL = "mail";
    /** Error codes of the API ("error" in the JSON body); also the reasons on /signed-out. */
    public static final String ERROR_SIGN_IN_REQUIRED = "sign_in_required";
    public static final String ERROR_SIGN_IN_FAILED = "sign_in_failed";
    public static final String ERROR_FORBIDDEN = "forbidden";
    public static final String ERROR_CSRF = "csrf";
    public static final String ERROR_NOT_CONFIGURED = "not_configured";

    // ---- InsureDirectory: kinds of entry (see directory.model.Kinds) -------------

    public static final String KIND_MEMBER = "member";
    public static final String KIND_AGENT = "agent";
    public static final String KIND_INTERNAL = "internal";
    public static final String KIND_OTHER = "other";
    public static final String KIND_AGENCY = "agency";
    public static final String KIND_AGREEMENT = "agreement";
    public static final String KIND_BOOK = "book";
    public static final String KIND_CLIENT = "client";
    public static final String KIND_UNDERWRITING_COMPANY = "underwriting-company";
    public static final String KIND_FEATURE = "feature";
    public static final String KIND_PERMISSION = "permission";

    // ---- Sync: CSV feed ----------------------------------------------------

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

    // ---- Crypto ----------------------------------------------------------------

    public static final String KEY_ALGORITHM_RSA = "RSA";
    public static final String DIGEST_SHA_256 = "SHA-256";
}
