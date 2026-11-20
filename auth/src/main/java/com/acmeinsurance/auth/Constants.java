package com.acmeinsurance.auth;

/**
 * Names shared across the token service: LDAP attributes, object classes and
 * entry names (see {@code openldap/ldif}), the OAuth 2.0 protocol vocabulary
 * (RFC 6749, RFC 7636) and the JWT claims. Use these instead of string literals,
 * so a rename happens in one place.
 *
 * <p>An enum with a single {@code INSTANCE} value: it cannot be instantiated or
 * subclassed, and holds only constants.
 */
public enum Constants {
    INSTANCE;

    // ---- LDAP attributes: standard -----------------------------------------

    public static final String OBJECT_CLASS = "objectClass";
    public static final String CN = "cn";
    public static final String SN = "sn";
    public static final String UID = "uid";
    public static final String GIVEN_NAME = "givenName";
    public static final String DISPLAY_NAME = "displayName";
    public static final String MAIL = "mail";
    public static final String USER_PASSWORD = "userPassword";
    public static final String ENTRY_UUID = "entryUUID";

    // ---- LDAP attributes: Acme Insurance schema ----------------------------

    public static final String ACME_USER_TYPE = "acmeUserType";
    public static final String ACME_ACCOUNT_DN = "acmeAccountDN";
    public static final String ACME_FEATURE_DN = "acmeFeatureDN";
    public static final String ACME_POLICY_NUMBER = "acmePolicyNumber";
    public static final String ACME_DEACTIVATED = "acmeDeactivated";
    public static final String ACME_REDIRECT_URI = "acmeRedirectUri";
    public static final String ACME_GRANT_TYPE = "acmeGrantType";

    // ---- LDAP attributes: password policy (ppolicy overlay) ----------------

    public static final String PWD_ACCOUNT_LOCKED_TIME = "pwdAccountLockedTime";
    public static final String PWD_CHANGED_TIME = "pwdChangedTime";
    public static final String PWD_RESET = "pwdReset";
    /** pwdAccountLockedTime value that locks an account until it is removed. */
    public static final String PERMANENT_LOCK = "000001010000Z";
    /** LDAP Boolean true. */
    public static final String LDAP_TRUE = "TRUE";

    // ---- Object classes ----------------------------------------------------

    public static final String OC_ACME_USER = "acmeUser";
    public static final String OC_ACME_FEATURE = "acmeFeature";
    public static final String OC_ACME_PERMISSION = "acmePermission";
    public static final String OC_ACME_OAUTH_CLIENT = "acmeOAuthClient";
    public static final String OC_ACME_AGENCY_ACCOUNT = "acmeAgencyAccount";
    public static final String OC_ACME_AGREEMENT_ACCOUNT = "acmeAgreementAccount";
    public static final String OC_ACME_BOOK_ACCOUNT = "acmeBookAccount";
    public static final String OC_ACME_CLIENT_ACCOUNT = "acmeClientAccount";

    // ---- Directory tree (RDNs below the base DN) ---------------------------

    public static final String RDN_PEOPLE = "ou=people";
    public static final String RDN_FEATURES = "ou=features";
    public static final String RDN_ACCOUNTS = "ou=accounts";
    public static final String RDN_AGENCIES = "ou=agencies";
    public static final String RDN_CLIENTS = "ou=clients";
    public static final String RDN_SERVICES = "ou=services";
    public static final String RDN_OAUTH_CLIENTS = "ou=oauth-clients";

    /** LDAP URL scheme for TLS connections. */
    public static final String LDAPS_SCHEME = "ldaps";

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

    // ---- JWT -----------------------------------------------------------------

    /** "typ" header of access tokens (RFC 9068). */
    public static final String TYP_ACCESS_TOKEN = "at+jwt";
    /** "typ" header of refresh tokens. */
    public static final String TYP_REFRESH_TOKEN = "rt+jwt";

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

    // ---- HTTP and crypto -----------------------------------------------------

    public static final String MEDIA_TYPE_JWK_SET = "application/jwk-set+json";
    public static final String HEADER_PRAGMA = "Pragma";
    public static final String PRAGMA_NO_CACHE = "no-cache";
    public static final String KEY_ALGORITHM_RSA = "RSA";
    public static final String DIGEST_SHA_256 = "SHA-256";
}
