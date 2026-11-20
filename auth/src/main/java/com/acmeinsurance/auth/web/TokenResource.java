package com.acmeinsurance.auth.web;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirementsSet;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
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
@Tag(name = "OAuth 2.0")
public final class TokenResource {

    private static final Logger LOG = Logger.getLogger(TokenResource.class);

    private final Directory directory;
    private final TokenService tokens;
    private final AuthorizationStore store;

    public TokenResource(final Directory directory, final TokenService tokens, final AuthorizationStore store) {
        this.directory = directory;
        this.tokens = tokens;
        this.store = store;
    }

    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Issue tokens",
            description = "Exchanges an authorization code (with its PKCE verifier), a username and password "
                    + "(confidential clients only) or a refresh token (confidential clients only) for an access "
                    + "token. A refresh re-reads the user from the directory and is refused for a locked, "
                    + "deactivated or reset user, or after a password change.")
    @RequestBody(required = true, content = @Content(mediaType = MediaType.APPLICATION_FORM_URLENCODED,
            schema = @Schema(ref = "TokenRequest")))
    @SecurityRequirementsSet(@SecurityRequirement(name = "clientBasic"))
    @SecurityRequirementsSet({})
    @APIResponse(responseCode = "200", description = "Tokens issued (Cache-Control: no-store)",
            content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(ref = "TokenResponse")))
    @APIResponse(responseCode = "400", description = "invalid_request, invalid_grant, unauthorized_client "
            + "or unsupported_grant_type",
            content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(ref = "OAuthError")))
    @APIResponse(responseCode = "401", description = "invalid_client (WWW-Authenticate: Basic when Basic was used)",
            content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(ref = "OAuthError")))
    public Response token(@Parameter(hidden = true) @HeaderParam("Authorization") final String authorization,
                          final MultivaluedMap<String, String> form)
            throws LDAPException {
        for (String name : form.keySet()) {
            if (form.get(name).size() > 1) {
                throw OAuthException.invalidRequest("parameter " + name + " repeated");
            }
        }
        final Client client = authenticateClient(authorization, form);
        final String grantType = required(form, "grant_type");
        if (!client.allows(grantType)) {
            if (!grantType.equals("authorization_code") && !grantType.equals("password")
                    && !grantType.equals("refresh_token")) {
                throw new OAuthException(400, "unsupported_grant_type", null);
            }
            throw new OAuthException(400, "unauthorized_client", "client may not use " + grantType);
        }
        final Map<String, Object> body = switch (grantType) {
            case "authorization_code" -> authorizationCode(client, form);
            case "password" -> password(client, form);
            case "refresh_token" -> refresh(client, form);
            default -> throw new OAuthException(400, "unsupported_grant_type", null);
        };
        final CacheControl cc = new CacheControl();
        cc.setNoStore(true);
        return Response.ok(body).cacheControl(cc).header("Pragma", "no-cache").build();
    }

    private Map<String, Object> authorizationCode(final Client client, final MultivaluedMap<String, String> form)
            throws LDAPException {
        final String codeValue = required(form, "code");
        final String redirectUri = required(form, "redirect_uri");
        final String verifier = required(form, "code_verifier");
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

    private Map<String, Object> password(final Client client,
                                         final MultivaluedMap<String, String> form) throws LDAPException {
        if (!client.confidential()) {
            throw new OAuthException(400, "unauthorized_client", "the password grant needs a confidential client");
        }
        final String username = required(form, "username");
        final String password = required(form, "password");
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

    private Map<String, Object> refresh(final Client client,
                                        final MultivaluedMap<String, String> form) throws LDAPException {
        if (!TokenService.issuesRefreshTokens(client)) {
            throw new OAuthException(400, "unauthorized_client", "refresh tokens are for confidential clients only");
        }
        final TokenService.Refresh r = tokens.verifyRefresh(required(form, "refresh_token"));
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

    /**
     * Client authentication: HTTP Basic (client_secret_basic), form parameters
     * (client_secret_post), or client_id alone for a public client.
     */
    private Client authenticateClient(final String authorization,
                                      final MultivaluedMap<String, String> form) throws LDAPException {
        String id = form.getFirst("client_id");
        String secret = form.getFirst("client_secret");
        final boolean basic = authorization != null && authorization.regionMatches(true, 0, "Basic ", 0, 6);
        if (basic) {
            if (secret != null) {
                throw OAuthException.invalidRequest("use one client authentication method");
            }
            String decoded;
            try {
                decoded = new String(Base64.getDecoder().decode(authorization.substring(6).trim()),
                        StandardCharsets.UTF_8);
            } catch (final IllegalArgumentException e) {
                throw new OAuthException(401, "invalid_client", null, true);
            }
            final int colon = decoded.indexOf(':');
            if (colon < 0) {
                throw new OAuthException(401, "invalid_client", null, true);
            }
            final String basicId = URLDecoder.decode(decoded.substring(0, colon), StandardCharsets.UTF_8);
            if (id != null && !id.equals(basicId)) {
                throw OAuthException.invalidRequest("client_id does not match the Authorization header");
            }
            id = basicId;
            secret = URLDecoder.decode(decoded.substring(colon + 1), StandardCharsets.UTF_8);
        }
        if (id == null || id.isBlank()) {
            throw new OAuthException(401, "invalid_client", "client authentication required", true);
        }
        final Client client = directory.findClient(id);
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

    private static String required(final MultivaluedMap<String, String> form, final String name) {
        final String v = form.getFirst(name);
        if (v == null || v.isEmpty()) {
            throw OAuthException.invalidRequest(name + " is required");
        }
        return v;
    }
}
