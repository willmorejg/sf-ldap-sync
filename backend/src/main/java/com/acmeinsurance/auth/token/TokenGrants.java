package com.acmeinsurance.auth.token;

import static com.acmeinsurance.common.Constants.ERROR_INVALID_CLIENT;
import static com.acmeinsurance.common.Constants.ERROR_UNAUTHORIZED_CLIENT;
import static com.acmeinsurance.common.Constants.ERROR_UNSUPPORTED_GRANT_TYPE;
import static com.acmeinsurance.common.Constants.GRANT_TYPE_AUTHORIZATION_CODE;
import static com.acmeinsurance.common.Constants.GRANT_TYPE_PASSWORD;
import static com.acmeinsurance.common.Constants.GRANT_TYPE_REFRESH_TOKEN;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import org.jboss.logging.Logger;

import com.acmeinsurance.auth.OAuthException;
import com.acmeinsurance.auth.ldap.Directory;
import com.acmeinsurance.auth.ldap.Directory.BindOutcome;
import com.acmeinsurance.auth.ldap.Directory.Client;
import com.acmeinsurance.auth.ldap.Directory.User;
import com.unboundid.ldap.sdk.LDAPException;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * The token endpoint's rules, apart from HTTP: client authentication and the
 * authorization_code, password and refresh_token grants. {@code web.TokenResource}
 * serves them at /oauth2/token; InsureDirectory calls them in-process as its own
 * confidential client, so both go through exactly the same checks.
 */
@ApplicationScoped
public class TokenGrants {

    private static final Logger LOG = Logger.getLogger(TokenGrants.class);
    private static final Set<String> GRANT_TYPES =
            Set.of(GRANT_TYPE_AUTHORIZATION_CODE, GRANT_TYPE_PASSWORD, GRANT_TYPE_REFRESH_TOKEN);

    private final Directory directory;
    private final TokenService tokens;
    private final AuthorizationStore store;

    public TokenGrants(final Directory directory, final TokenService tokens, final AuthorizationStore store) {
        this.directory = directory;
        this.tokens = tokens;
        this.store = store;
    }

    /**
     * The client with this ID, checked against its secret (confidential clients) or
     * required to send none (public clients).
     *
     * @param basic whether the credentials came in an HTTP Basic header (for the 401 challenge)
     */
    public Client authenticateClient(final String id, final String secret, final boolean basic)
            throws LDAPException {
        if (id == null || id.isBlank()) {
            throw new OAuthException(401, ERROR_INVALID_CLIENT, "client authentication required", true);
        }
        final Client client = directory.findClient(id);
        if (client == null) {
            throw new OAuthException(401, ERROR_INVALID_CLIENT, null, basic);
        }
        if (client.confidential()) {
            if (!directory.authenticateClient(client, secret)) {
                throw new OAuthException(401, ERROR_INVALID_CLIENT, null, basic);
            }
        } else if (secret != null && !secret.isEmpty()) {
            throw new OAuthException(401, ERROR_INVALID_CLIENT, "public client has no secret", basic);
        }
        return client;
    }

    /** Refuses a grant type the client is not registered for (or that does not exist). */
    public void checkGrantType(final Client client, final String grantType) {
        if (client.allows(grantType)) {
            return;
        }
        if (!GRANT_TYPES.contains(grantType)) {
            throw new OAuthException(400, ERROR_UNSUPPORTED_GRANT_TYPE, null);
        }
        throw new OAuthException(400, ERROR_UNAUTHORIZED_CLIENT, "client may not use " + grantType);
    }

    public Map<String, Object> authorizationCode(final Client client, final String codeValue,
                                                 final String redirectUri, final String verifier)
            throws LDAPException {
        checkGrantType(client, GRANT_TYPE_AUTHORIZATION_CODE);
        final AuthorizationStore.Code code = store.takeCode(codeValue);
        if (code == null || !code.clientId().equals(client.clientId())) {
            throw OAuthException.invalidGrant("invalid or expired code");
        }
        if (!code.redirectUri().equals(redirectUri)) {
            throw OAuthException.invalidGrant("redirect_uri does not match the authorization request");
        }
        if (!Pkce.matches(verifier, code.codeChallenge())) {
            throw OAuthException.invalidGrant("code_verifier does not match");
        }
        final User user = activeUser(directory.findById(code.userId()));
        return tokens.issue(user, client, code.authTime());
    }

    public Map<String, Object> password(final Client client, final String username, final String password)
            throws LDAPException {
        checkGrantType(client, GRANT_TYPE_PASSWORD);
        if (!client.confidential()) {
            throw new OAuthException(400, ERROR_UNAUTHORIZED_CLIENT, "the password grant needs a confidential client");
        }
        final User user = directory.findByUsername(username);
        if (user == null || user.blocked()) {
            throw OAuthException.invalidGrant("invalid username or password");
        }
        final BindOutcome outcome = directory.authenticate(user, password);
        switch (outcome) {
            case OK -> { }
            case MUST_CHANGE -> throw OAuthException.invalidGrant("password change required");
            case EXPIRED -> throw OAuthException.invalidGrant("password expired");
            default -> throw OAuthException.invalidGrant("invalid username or password");
        }
        // Re-read: the bind may have cleared a ppolicy lockout timestamp.
        final User fresh = activeUser(directory.findById(user.id()));
        LOG.infof("password grant: %s for client %s", fresh.uid(), client.clientId());
        return tokens.issue(fresh, client, Instant.now());
    }

    public Map<String, Object> refresh(final Client client, final String refreshToken) throws LDAPException {
        checkGrantType(client, GRANT_TYPE_REFRESH_TOKEN);
        if (!TokenService.issuesRefreshTokens(client)) {
            throw new OAuthException(400, ERROR_UNAUTHORIZED_CLIENT,
                    "refresh tokens are for confidential clients only");
        }
        final TokenService.Refresh r = tokens.verifyRefresh(refreshToken);
        if (!client.clientId().equals(r.clientId())) {
            throw OAuthException.invalidGrant("refresh token was issued to another client");
        }
        final User user = activeUser(directory.findById(r.subject()));
        if (user.passwordChanged() != null && user.passwordChanged().isAfter(r.authTime())) {
            throw OAuthException.invalidGrant("password changed since sign-in; sign in again");
        }
        return tokens.issue(user, client, r.authTime());
    }

    /** The user must exist, not be locked or deactivated, and not be waiting for a password change. */
    private static User activeUser(final User user) {
        if (user == null || user.disabled() || user.passwordReset()) {
            throw OAuthException.invalidGrant("user is not active; sign in again");
        }
        return user;
    }
}
