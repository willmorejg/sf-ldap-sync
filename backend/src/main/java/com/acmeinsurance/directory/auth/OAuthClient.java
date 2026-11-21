package com.acmeinsurance.directory.auth;

import static com.acmeinsurance.common.Constants.PARAM_ACCESS_TOKEN;
import static com.acmeinsurance.common.Constants.PARAM_CLIENT_ID;
import static com.acmeinsurance.common.Constants.PARAM_CODE_CHALLENGE;
import static com.acmeinsurance.common.Constants.PARAM_CODE_CHALLENGE_METHOD;
import static com.acmeinsurance.common.Constants.PARAM_REDIRECT_URI;
import static com.acmeinsurance.common.Constants.PARAM_REFRESH_TOKEN;
import static com.acmeinsurance.common.Constants.PARAM_RESPONSE_TYPE;
import static com.acmeinsurance.common.Constants.PARAM_STATE;
import static com.acmeinsurance.common.Constants.PATH_APP_CALLBACK;
import static com.acmeinsurance.common.Constants.PATH_AUTHORIZE;
import static com.acmeinsurance.common.Constants.PKCE_METHOD_S256;
import static com.acmeinsurance.common.Constants.RESPONSE_TYPE_CODE;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.jboss.logging.Logger;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.MalformedClaimException;

import com.acmeinsurance.auth.OAuthException;
import com.acmeinsurance.auth.ldap.Directory.Client;
import com.acmeinsurance.auth.token.TokenGrants;
import com.acmeinsurance.auth.token.TokenService;
import com.acmeinsurance.common.web.ApiException;
import com.acmeinsurance.directory.DirectoryConfig;
import com.unboundid.ldap.sdk.LDAPException;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * InsureDirectory as a confidential client of the token service in this app. The
 * browser still signs in on the token service's page (/oauth2/authorize); the code
 * and refresh tokens are redeemed in-process through {@link TokenGrants}, with the
 * client's own ID and secret, so the token endpoint's checks all apply.
 */
@ApplicationScoped
public class OAuthClient {

    /** Tokens from one grant, the access token already verified. */
    public record Tokens(String accessToken, String refreshToken, JwtClaims claims, Instant accessExpires) {
    }

    @FunctionalInterface
    private interface Grant {
        Map<String, Object> run(Client client) throws LDAPException;
    }

    private static final Logger LOG = Logger.getLogger(OAuthClient.class);
    private static final String SIGN_IN_FAILED = "sign_in_failed";
    private static final String NOT_CONFIGURED = "not_configured";

    private final DirectoryConfig cfg;
    private final TokenGrants grants;
    private final TokenService tokens;

    public OAuthClient(final DirectoryConfig cfg, final TokenGrants grants, final TokenService tokens) {
        this.cfg = cfg;
        this.grants = grants;
        this.tokens = tokens;
    }

    public String redirectUri() {
        return stripSlash(cfg.publicUrl()) + PATH_APP_CALLBACK;
    }

    /**
     * Where to send the browser: the token service's sign-in page (code flow with PKCE
     * S256). A path, not a URL: the page is on this origin (and on the Angular dev
     * server's, through its proxy).
     */
    public String authorizeUrl(final String state, final String codeChallenge) {
        final Map<String, String> q = new LinkedHashMap<>();
        q.put(PARAM_RESPONSE_TYPE, RESPONSE_TYPE_CODE);
        q.put(PARAM_CLIENT_ID, cfg.auth().clientId());
        q.put(PARAM_REDIRECT_URI, redirectUri());
        q.put(PARAM_STATE, state);
        q.put(PARAM_CODE_CHALLENGE, codeChallenge);
        q.put(PARAM_CODE_CHALLENGE_METHOD, PKCE_METHOD_S256);
        return PATH_AUTHORIZE + "?" + q.entrySet().stream()
                .map(e -> enc(e.getKey()) + "=" + enc(e.getValue())).collect(Collectors.joining("&"));
    }

    public Tokens redeemCode(final String code, final String verifier) {
        return grant(client -> grants.authorizationCode(client, code, redirectUri(), verifier));
    }

    public Tokens refresh(final String refreshToken) {
        return grant(client -> grants.refresh(client, refreshToken));
    }

    private Tokens grant(final Grant grant) {
        final String secret = cfg.auth().clientSecret().orElseThrow(() -> new ApiException(503,
                NOT_CONFIGURED, "DIRECTORY_CLIENT_SECRET is not set"));
        final Map<String, Object> body;
        try {
            body = grant.run(grants.authenticateClient(cfg.auth().clientId(), secret, false));
        } catch (final OAuthException e) {
            LOG.infof("token grant refused: %s (%s)", e.error(), e.getMessage());
            throw new ApiException(401, SIGN_IN_FAILED, "the token service refused: " + e.error());
        } catch (final LDAPException e) {
            throw new ApiException(503, SIGN_IN_FAILED, "the directory is not available: " + e.getMessage());
        }
        final String access = (String) body.get(PARAM_ACCESS_TOKEN);
        final JwtClaims claims = tokens.verifyAccess(access);
        try {
            return new Tokens(access, (String) body.get(PARAM_REFRESH_TOKEN), claims,
                    Instant.ofEpochSecond(claims.getExpirationTime().getValue()));
        } catch (final MalformedClaimException e) {
            throw new ApiException(401, SIGN_IN_FAILED, "access token without exp");
        }
    }

    private static String enc(final String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String stripSlash(final String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
