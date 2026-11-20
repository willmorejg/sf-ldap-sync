package com.acmeinsurance.auth.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.acmeinsurance.auth.AuthConfig;
import com.acmeinsurance.auth.token.SigningKeys;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** Authorization server metadata (RFC 8414) and the JWKS. */
@Path("/")
public class MetadataResource {

    private final AuthConfig cfg;
    private final SigningKeys keys;

    public MetadataResource(AuthConfig cfg, SigningKeys keys) {
        this.cfg = cfg;
        this.keys = keys;
    }

    @GET
    @Path(".well-known/oauth-authorization-server")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> metadata() {
        String iss = cfg.issuer();
        Map<String, Object> m = new LinkedHashMap<>();
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
    public String jwks() {
        return keys.jwks();
    }
}
