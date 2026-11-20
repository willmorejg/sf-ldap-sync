package com.acmeinsurance.auth.web;

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
    @Path(".well-known/oauth-authorization-server")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Authorization server metadata (RFC 8414)")
    @APIResponse(responseCode = "200", description = "Endpoints and supported features",
            content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(ref = "ServerMetadata")))
    public Map<String, Object> metadata() {
        final String iss = cfg.issuer();
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("issuer", iss);
        m.put("authorization_endpoint", iss + "/oauth2/authorize");
        m.put("token_endpoint", iss + "/oauth2/token");
        m.put("jwks_uri", iss + "/oauth2/jwks");
        m.put("response_types_supported", List.of("code"));
        m.put("grant_types_supported", List.of("authorization_code", "password", "refresh_token"));
        m.put("code_challenge_methods_supported", List.of("S256"));
        m.put("token_endpoint_auth_methods_supported", List.of("client_secret_basic", "client_secret_post", "none"));
        return m;
    }

    @GET
    @Path("oauth2/jwks")
    @Produces("application/jwk-set+json")
    @Operation(summary = "Token signing keys (JWKS)",
            description = "Public keys for verifying access tokens. Match a token's kid header to a key; "
                    + "cache the set and fetch it again when an unknown kid appears.")
    @APIResponse(responseCode = "200", description = "The key set",
            content = @Content(mediaType = "application/jwk-set+json", schema = @Schema(ref = "JsonWebKeySet")))
    public String jwks() {
        return keys.jwks();
    }
}
