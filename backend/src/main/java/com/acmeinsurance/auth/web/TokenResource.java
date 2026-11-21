package com.acmeinsurance.auth.web;

import static com.acmeinsurance.common.Constants.AUTH_SCHEME_BASIC;
import static com.acmeinsurance.common.Constants.ERROR_INVALID_CLIENT;
import static com.acmeinsurance.common.Constants.ERROR_UNSUPPORTED_GRANT_TYPE;
import static com.acmeinsurance.common.Constants.GRANT_TYPE_AUTHORIZATION_CODE;
import static com.acmeinsurance.common.Constants.GRANT_TYPE_PASSWORD;
import static com.acmeinsurance.common.Constants.GRANT_TYPE_REFRESH_TOKEN;
import static com.acmeinsurance.common.Constants.HEADER_AUTHORIZATION;
import static com.acmeinsurance.common.Constants.HEADER_PRAGMA;
import static com.acmeinsurance.common.Constants.PARAM_CLIENT_ID;
import static com.acmeinsurance.common.Constants.PARAM_CLIENT_SECRET;
import static com.acmeinsurance.common.Constants.PARAM_CODE;
import static com.acmeinsurance.common.Constants.PARAM_CODE_VERIFIER;
import static com.acmeinsurance.common.Constants.PARAM_GRANT_TYPE;
import static com.acmeinsurance.common.Constants.PARAM_PASSWORD;
import static com.acmeinsurance.common.Constants.PARAM_REDIRECT_URI;
import static com.acmeinsurance.common.Constants.PARAM_REFRESH_TOKEN;
import static com.acmeinsurance.common.Constants.PARAM_USERNAME;
import static com.acmeinsurance.common.Constants.PATH_TOKEN;
import static com.acmeinsurance.common.Constants.PRAGMA_NO_CACHE;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
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

import com.acmeinsurance.auth.OAuthException;
import com.acmeinsurance.auth.ldap.Directory.Client;
import com.acmeinsurance.auth.token.TokenGrants;
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
@Path(PATH_TOKEN)
@Tag(name = "OAuth 2.0")
public final class TokenResource {

    private final TokenGrants grants;

    public TokenResource(final TokenGrants grants) {
        this.grants = grants;
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
    public Response token(@Parameter(hidden = true) @HeaderParam(HEADER_AUTHORIZATION) final String authorization,
                          final MultivaluedMap<String, String> form)
            throws LDAPException {
        for (String name : form.keySet()) {
            if (form.get(name).size() > 1) {
                throw OAuthException.invalidRequest("parameter " + name + " repeated");
            }
        }
        final Client client = authenticateClient(authorization, form);
        final String grantType = required(form, PARAM_GRANT_TYPE);
        grants.checkGrantType(client, grantType);
        final Map<String, Object> body = switch (grantType) {
            case GRANT_TYPE_AUTHORIZATION_CODE -> grants.authorizationCode(client, required(form, PARAM_CODE),
                    required(form, PARAM_REDIRECT_URI), required(form, PARAM_CODE_VERIFIER));
            case GRANT_TYPE_PASSWORD -> grants.password(client, required(form, PARAM_USERNAME),
                    required(form, PARAM_PASSWORD));
            case GRANT_TYPE_REFRESH_TOKEN -> grants.refresh(client, required(form, PARAM_REFRESH_TOKEN));
            default -> throw new OAuthException(400, ERROR_UNSUPPORTED_GRANT_TYPE, null);
        };
        final CacheControl cc = new CacheControl();
        cc.setNoStore(true);
        return Response.ok(body).cacheControl(cc).header(HEADER_PRAGMA, PRAGMA_NO_CACHE).build();
    }

    /**
     * Client authentication: HTTP Basic (client_secret_basic), form parameters
     * (client_secret_post), or client_id alone for a public client.
     */
    private Client authenticateClient(final String authorization,
                                      final MultivaluedMap<String, String> form) throws LDAPException {
        String id = form.getFirst(PARAM_CLIENT_ID);
        String secret = form.getFirst(PARAM_CLIENT_SECRET);
        final String basicPrefix = AUTH_SCHEME_BASIC + " ";
        final boolean basic = authorization != null
                && authorization.regionMatches(true, 0, basicPrefix, 0, basicPrefix.length());
        if (basic) {
            if (secret != null) {
                throw OAuthException.invalidRequest("use one client authentication method");
            }
            String decoded;
            try {
                decoded = new String(Base64.getDecoder().decode(authorization.substring(basicPrefix.length()).trim()),
                        StandardCharsets.UTF_8);
            } catch (final IllegalArgumentException e) {
                throw new OAuthException(401, ERROR_INVALID_CLIENT, null, true);
            }
            final int colon = decoded.indexOf(':');
            if (colon < 0) {
                throw new OAuthException(401, ERROR_INVALID_CLIENT, null, true);
            }
            final String basicId = URLDecoder.decode(decoded.substring(0, colon), StandardCharsets.UTF_8);
            if (id != null && !id.equals(basicId)) {
                throw OAuthException.invalidRequest("client_id does not match the Authorization header");
            }
            id = basicId;
            secret = URLDecoder.decode(decoded.substring(colon + 1), StandardCharsets.UTF_8);
        }
        return grants.authenticateClient(id, secret, basic);
    }

    private static String required(final MultivaluedMap<String, String> form, final String name) {
        final String v = form.getFirst(name);
        if (v == null || v.isEmpty()) {
            throw OAuthException.invalidRequest(name + " is required");
        }
        return v;
    }
}
