package com.acmeinsurance.sync.web;

import static com.acmeinsurance.common.Constants.AUTH_REALM;
import static com.acmeinsurance.common.Constants.ERROR_INSUFFICIENT_SCOPE;
import static com.acmeinsurance.common.Constants.ERROR_INVALID_TOKEN;
import static com.acmeinsurance.common.Constants.FEATURE_SYNC;
import static com.acmeinsurance.common.Constants.HEADER_AUTHORIZATION;
import static com.acmeinsurance.common.Constants.PERMISSION_RUN_SYNC;
import static com.acmeinsurance.common.Constants.TOKEN_TYPE_BEARER;

import org.apache.camel.Header;
import org.jboss.logging.Logger;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.MalformedClaimException;

import com.acmeinsurance.auth.OAuthException;
import com.acmeinsurance.auth.token.TokenService;
import com.acmeinsurance.common.web.ApiException;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;

/**
 * The first step of the /sync/salesforce routes: the request must carry an access
 * token from this app's token service (Authorization: Bearer, RFC 6750) whose
 * "features" claim grants sync > run-sync (38-sync-feature.ldif). The trigger used
 * to rely on a loopback-only port; it now shares the public port with the token
 * service.
 */
@ApplicationScoped
@Named("syncGuard")
public class SyncGuard {

    private static final Logger LOG = Logger.getLogger(SyncGuard.class);
    private static final String BEARER_PREFIX = TOKEN_TYPE_BEARER + " ";
    private static final String REALM = TOKEN_TYPE_BEARER + " realm=\"" + AUTH_REALM + "\"";

    private final TokenService tokens;

    public SyncGuard(final TokenService tokens) {
        this.tokens = tokens;
    }

    public void check(@Header(HEADER_AUTHORIZATION) final String authorization) {
        if (authorization == null
                || !authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            throw new ApiException(401, ERROR_INVALID_TOKEN, "an access token is required", REALM);
        }
        final JwtClaims claims;
        try {
            claims = tokens.verifyAccess(authorization.substring(BEARER_PREFIX.length()).trim());
        } catch (final OAuthException e) {
            throw new ApiException(401, ERROR_INVALID_TOKEN, e.getMessage(),
                    REALM + ", error=\"" + ERROR_INVALID_TOKEN + "\"");
        }
        if (!TokenService.permissions(claims, FEATURE_SYNC).contains(PERMISSION_RUN_SYNC)) {
            throw new ApiException(403, ERROR_INSUFFICIENT_SCOPE,
                    "the token does not grant " + FEATURE_SYNC + " > " + PERMISSION_RUN_SYNC,
                    REALM + ", error=\"" + ERROR_INSUFFICIENT_SCOPE + "\"");
        }
        LOG.infof("sync trigger by %s", subject(claims));
    }

    private static String subject(final JwtClaims claims) {
        try {
            return claims.getSubject();
        } catch (final MalformedClaimException e) {
            return null;
        }
    }
}
