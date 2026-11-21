package com.acmeinsurance.auth;

import static com.acmeinsurance.common.Constants.AUTH_REALM;
import static com.acmeinsurance.common.Constants.AUTH_SCHEME_BASIC;
import static com.acmeinsurance.common.Constants.ERROR_INVALID_GRANT;
import static com.acmeinsurance.common.Constants.ERROR_INVALID_REQUEST;
import static com.acmeinsurance.common.Constants.HEADER_PRAGMA;
import static com.acmeinsurance.common.Constants.HEADER_WWW_AUTHENTICATE;
import static com.acmeinsurance.common.Constants.PARAM_ERROR;
import static com.acmeinsurance.common.Constants.PARAM_ERROR_DESCRIPTION;
import static com.acmeinsurance.common.Constants.PRAGMA_NO_CACHE;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.ws.rs.core.CacheControl;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/** An RFC 6749 error response from the token endpoint. */
public final class OAuthException extends RuntimeException {

    /** Realm of the Basic challenge for client authentication. */

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
        return new OAuthException(400, ERROR_INVALID_REQUEST, description);
    }

    public static OAuthException invalidGrant(final String description) {
        return new OAuthException(400, ERROR_INVALID_GRANT, description);
    }

    public String error() {
        return error;
    }

    public int status() {
        return status;
    }

    @Provider
    public static final class Mapper implements ExceptionMapper<OAuthException> {
        @Override
        public Response toResponse(final OAuthException e) {
            final Map<String, String> body = new LinkedHashMap<>();
            body.put(PARAM_ERROR, e.error);
            if (e.getMessage() != null) {
                body.put(PARAM_ERROR_DESCRIPTION, e.getMessage());
            }
            final CacheControl cc = new CacheControl();
            cc.setNoStore(true);
            final Response.ResponseBuilder rb = Response.status(e.status).type(MediaType.APPLICATION_JSON_TYPE)
                    .cacheControl(cc).header(HEADER_PRAGMA, PRAGMA_NO_CACHE).entity(body);
            if (e.basicChallenge) {
                rb.header(HEADER_WWW_AUTHENTICATE, AUTH_SCHEME_BASIC + " realm=\"" + AUTH_REALM + "\"");
            }
            return rb.build();
        }
    }
}
