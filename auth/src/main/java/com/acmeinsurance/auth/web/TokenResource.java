package com.acmeinsurance.auth.web;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

import org.jboss.logging.Logger;

import com.acmeinsurance.auth.OAuthException;
import com.acmeinsurance.auth.ldap.Directory;
import com.acmeinsurance.auth.ldap.Directory.BindOutcome;
import com.acmeinsurance.auth.ldap.Directory.Client;
import com.acmeinsurance.auth.ldap.Directory.User;
import com.acmeinsurance.auth.token.AuthorizationStore;
import com.acmeinsurance.auth.token.Pkce;
import com.acmeinsurance.auth.token.TokenService;
import com.unboundid.ldap.sdk.LDAPException;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.CacheControl;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;

/** The token endpoint: authorization_code (with PKCE), password and refresh_token grants. */
@Path("/oauth2/token")
public class TokenResource {

    private static final Logger LOG = Logger.getLogger(TokenResource.class);

    private final Directory directory;
    private final TokenService tokens;
    private final AuthorizationStore store;

    public TokenResource(Directory directory, TokenService tokens, AuthorizationStore store) {
        this.directory = directory;
        this.tokens = tokens;
        this.store = store;
    }

    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    public Response token(@HeaderParam("Authorization") String authorization, MultivaluedMap<String, String> form)
            throws LDAPException {
        for (String name : form.keySet()) {
            if (form.get(name).size() > 1) {
                throw OAuthException.invalidRequest("parameter " + name + " repeated");
            }
        }
        Client client = authenticateClient(authorization, form);
        String grantType = required(form, "grant_type");
        if (!client.allows(grantType)) {
            if (!grantType.equals("authorization_code") && !grantType.equals("password")
                    && !grantType.equals("refresh_token")) {
                throw new OAuthException(400, "unsupported_grant_type", null);
            }
            throw new OAuthException(400, "unauthorized_client", "client may not use " + grantType);
        }
        Map<String, Object> body = switch (grantType) {
            case "authorization_code" -> authorizationCode(client, form);
            case "password" -> password(client, form);
            case "refresh_token" -> refresh(client, form);
            default -> throw new OAuthException(400, "unsupported_grant_type", null);
        };
        CacheControl cc = new CacheControl();
        cc.setNoStore(true);
        return Response.ok(body).cacheControl(cc).header("Pragma", "no-cache").build();
    }

    private Map<String, Object> authorizationCode(Client client, MultivaluedMap<String, String> form)
            throws LDAPException {
        String codeValue = required(form, "code");
        String redirectUri = required(form, "redirect_uri");
        String verifier = required(form, "code_verifier");
        AuthorizationStore.Code code = store.takeCode(codeValue);
        if (code == null || !code.clientId().equals(client.clientId())) {
            throw OAuthException.invalidGrant("invalid or expired code");
        }
        if (!code.redirectUri().equals(redirectUri)) {
            throw OAuthException.invalidGrant("redirect_uri does not match the authorization request");
        }
        if (!Pkce.matches(verifier, code.codeChallenge())) {
            throw OAuthException.invalidGrant("code_verifier does not match");
        }
        User user = activeUser(directory.findById(code.userId()));
        return tokens.issue(user, client, code.authTime());
    }

    private Map<String, Object> password(Client client, MultivaluedMap<String, String> form) throws LDAPException {
        if (!client.confidential()) {
            throw new OAuthException(400, "unauthorized_client", "the password grant needs a confidential client");
        }
        String username = required(form, "username");
        String password = required(form, "password");
        User user = directory.findByUsername(username);
        if (user == null || user.blocked()) {
            throw OAuthException.invalidGrant("invalid username or password");
        }
        BindOutcome outcome = directory.authenticate(user, password);
        switch (outcome) {
            case OK -> { }
            case MUST_CHANGE -> throw OAuthException.invalidGrant("password change required");
            case EXPIRED -> throw OAuthException.invalidGrant("password expired");
            default -> throw OAuthException.invalidGrant("invalid username or password");
        }
        // Re-read: the bind may have cleared a ppolicy lockout timestamp.
        User fresh = activeUser(directory.findById(user.id()));
        LOG.infof("password grant: %s for client %s", fresh.uid(), client.clientId());
        return tokens.issue(fresh, client, Instant.now());
    }

    private Map<String, Object> refresh(Client client, MultivaluedMap<String, String> form) throws LDAPException {
        if (!TokenService.issuesRefreshTokens(client)) {
            throw new OAuthException(400, "unauthorized_client", "refresh tokens are for confidential clients only");
        }
        TokenService.Refresh r = tokens.verifyRefresh(required(form, "refresh_token"));
        if (!client.clientId().equals(r.clientId())) {
            throw OAuthException.invalidGrant("refresh token was issued to another client");
        }
        User user = activeUser(directory.findById(r.subject()));
        if (user.passwordChanged() != null && user.passwordChanged().isAfter(r.authTime())) {
            throw OAuthException.invalidGrant("password changed since sign-in; sign in again");
        }
        return tokens.issue(user, client, r.authTime());
    }

    /** The user must exist, not be locked or deactivated, and not be waiting for a password change. */
    private static User activeUser(User user) {
        if (user == null || user.disabled() || user.passwordReset()) {
            throw OAuthException.invalidGrant("user is not active; sign in again");
        }
        return user;
    }

    /**
     * Client authentication: HTTP Basic (client_secret_basic), form parameters
     * (client_secret_post), or client_id alone for a public client.
     */
    private Client authenticateClient(String authorization, MultivaluedMap<String, String> form) throws LDAPException {
        String id = form.getFirst("client_id");
        String secret = form.getFirst("client_secret");
        boolean basic = authorization != null && authorization.regionMatches(true, 0, "Basic ", 0, 6);
        if (basic) {
            if (secret != null) {
                throw OAuthException.invalidRequest("use one client authentication method");
            }
            String decoded;
            try {
                decoded = new String(Base64.getDecoder().decode(authorization.substring(6).trim()),
                        StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                throw new OAuthException(401, "invalid_client", null, true);
            }
            int colon = decoded.indexOf(':');
            if (colon < 0) {
                throw new OAuthException(401, "invalid_client", null, true);
            }
            String basicId = URLDecoder.decode(decoded.substring(0, colon), StandardCharsets.UTF_8);
            if (id != null && !id.equals(basicId)) {
                throw OAuthException.invalidRequest("client_id does not match the Authorization header");
            }
            id = basicId;
            secret = URLDecoder.decode(decoded.substring(colon + 1), StandardCharsets.UTF_8);
        }
        if (id == null || id.isBlank()) {
            throw new OAuthException(401, "invalid_client", "client authentication required", true);
        }
        Client client = directory.findClient(id);
        if (client == null) {
            throw new OAuthException(401, "invalid_client", null, basic);
        }
        if (client.confidential()) {
            if (!directory.authenticateClient(client, secret)) {
                throw new OAuthException(401, "invalid_client", null, basic);
            }
        } else if (secret != null && !secret.isEmpty()) {
            throw new OAuthException(401, "invalid_client", "public client has no secret", basic);
        }
        return client;
    }

    private static String required(MultivaluedMap<String, String> form, String name) {
        String v = form.getFirst(name);
        if (v == null || v.isEmpty()) {
            throw OAuthException.invalidRequest(name + " is required");
        }
        return v;
    }
}
