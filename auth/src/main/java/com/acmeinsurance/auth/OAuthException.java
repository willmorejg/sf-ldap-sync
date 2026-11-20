package com.acmeinsurance.auth;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.ws.rs.core.CacheControl;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/** An RFC 6749 error response from the token endpoint. */
public final class OAuthException extends RuntimeException {

    private final int status;
    private final String error;
    private final boolean basicChallenge;

    public OAuthException(final int status, final String error, final String description) {
        this(status, error, description, false);
    }

    public OAuthException(final int status, final String error, final String description,
                          final boolean basicChallenge) {
        super(description);
        this.status = status;
        this.error = error;
        this.basicChallenge = basicChallenge;
    }

    public static OAuthException invalidRequest(final String description) {
        return new OAuthException(400, "invalid_request", description);
    }

    public static OAuthException invalidGrant(final String description) {
        return new OAuthException(400, "invalid_grant", description);
    }

    public String error() {
        return error;
    }

    @Provider
    public static final class Mapper implements ExceptionMapper<OAuthException> {
        @Override
        public Response toResponse(final OAuthException e) {
            final Map<String, String> body = new LinkedHashMap<>();
            body.put("error", e.error);
            if (e.getMessage() != null) {
                body.put("error_description", e.getMessage());
            }
            final CacheControl cc = new CacheControl();
            cc.setNoStore(true);
            final Response.ResponseBuilder rb = Response.status(e.status).type(MediaType.APPLICATION_JSON_TYPE)
                    .cacheControl(cc).header("Pragma", "no-cache").entity(body);
            if (e.basicChallenge) {
                rb.header("WWW-Authenticate", "Basic realm=\"acmeinsurance\"");
            }
            return rb.build();
        }
    }
}
