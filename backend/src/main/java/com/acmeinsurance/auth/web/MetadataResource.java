package com.acmeinsurance.auth.web;

import static com.acmeinsurance.common.Constants.GRANT_TYPE_AUTHORIZATION_CODE;
import static com.acmeinsurance.common.Constants.GRANT_TYPE_PASSWORD;
import static com.acmeinsurance.common.Constants.GRANT_TYPE_REFRESH_TOKEN;
import static com.acmeinsurance.common.Constants.MEDIA_TYPE_JWK_SET;
import static com.acmeinsurance.common.Constants.PATH_AUTHORIZE;
import static com.acmeinsurance.common.Constants.PATH_JWKS;
import static com.acmeinsurance.common.Constants.PATH_SERVER_METADATA;
import static com.acmeinsurance.common.Constants.PATH_TOKEN;
import static com.acmeinsurance.common.Constants.PKCE_METHOD_S256;
import static com.acmeinsurance.common.Constants.RESPONSE_TYPE_CODE;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.acmeinsurance.auth.AuthConfig;
import com.acmeinsurance.auth.token.SigningKeys;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** Authorization server metadata (RFC 8414) and the JWKS. */
@Path("/")
@Tag(name = "Discovery")
public final class MetadataResource {

    private final AuthConfig cfg;
    private final SigningKeys keys;

    public MetadataResource(final AuthConfig cfg, final SigningKeys keys) {
        this.cfg = cfg;
        this.keys = keys;
    }

    @GET
    @Path(PATH_SERVER_METADATA)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Authorization server metadata (RFC 8414)")
    @APIResponse(responseCode = "200", description = "Endpoints and supported features",
            content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(ref = "ServerMetadata")))
    public Map<String, Object> metadata() {
        final String iss = cfg.issuer();
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("issuer", iss);
        m.put("authorization_endpoint", iss + PATH_AUTHORIZE);
        m.put("token_endpoint", iss + PATH_TOKEN);
        m.put("jwks_uri", iss + PATH_JWKS);
        m.put("response_types_supported", List.of(RESPONSE_TYPE_CODE));
        m.put("grant_types_supported",
                List.of(GRANT_TYPE_AUTHORIZATION_CODE, GRANT_TYPE_PASSWORD, GRANT_TYPE_REFRESH_TOKEN));
        m.put("code_challenge_methods_supported", List.of(PKCE_METHOD_S256));
        m.put("token_endpoint_auth_methods_supported", List.of("client_secret_basic", "client_secret_post", "none"));
        return m;
    }

    @GET
    @Path(PATH_JWKS)
    @Produces(MEDIA_TYPE_JWK_SET)
    @Operation(summary = "Token signing keys (JWKS)",
            description = "Public keys for verifying access tokens. Match a token's kid header to a key; "
                    + "cache the set and fetch it again when an unknown kid appears.")
    @APIResponse(responseCode = "200", description = "The key set",
            content = @Content(mediaType = MEDIA_TYPE_JWK_SET, schema = @Schema(ref = "JsonWebKeySet")))
    public String jwks() {
        return keys.jwks();
    }
}
