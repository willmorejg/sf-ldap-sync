package com.acmeinsurance.directory.auth;

import static com.acmeinsurance.common.Constants.CACHE_NO_STORE;
import static com.acmeinsurance.common.Constants.CLAIM_NAME;
import static com.acmeinsurance.common.Constants.CLAIM_PREFERRED_USERNAME;
import static com.acmeinsurance.common.Constants.CLAIM_SUBJECT;
import static com.acmeinsurance.common.Constants.CLAIM_USER_TYPE;
import static com.acmeinsurance.common.Constants.DIGEST_SHA_256;
import static com.acmeinsurance.common.Constants.ERROR_FORBIDDEN;
import static com.acmeinsurance.common.Constants.ERROR_SIGN_IN_FAILED;
import static com.acmeinsurance.common.Constants.FEATURE_DIRECTORY;
import static com.acmeinsurance.common.Constants.HEADER_CACHE_CONTROL;
import static com.acmeinsurance.common.Constants.HEADER_COOKIE;
import static com.acmeinsurance.common.Constants.HEADER_LOCATION;
import static com.acmeinsurance.common.Constants.HEADER_SET_COOKIE;
import static com.acmeinsurance.common.Constants.JSON_NAME;
import static com.acmeinsurance.common.Constants.JSON_PERMISSIONS;
import static com.acmeinsurance.common.Constants.JSON_SIGNED_OUT;
import static com.acmeinsurance.common.Constants.JSON_USERNAME;
import static com.acmeinsurance.common.Constants.PARAM_CODE;
import static com.acmeinsurance.common.Constants.PARAM_ERROR;
import static com.acmeinsurance.common.Constants.PARAM_RETURN_TO;
import static com.acmeinsurance.common.Constants.PARAM_STATE;
import static com.acmeinsurance.common.Constants.PATH_APP_AUTH;
import static com.acmeinsurance.common.Constants.PATH_SIGNED_OUT;
import static com.acmeinsurance.common.Constants.PERMISSION_VIEW;
import static com.acmeinsurance.common.Constants.PROPERTY_SESSION;
import static com.acmeinsurance.common.Constants.SCHEME_HTTPS;
import static com.acmeinsurance.common.Constants.SESSION_COOKIE;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.apache.camel.Exchange;
import org.apache.camel.ExchangeProperty;
import org.jboss.logging.Logger;
import org.jose4j.jwt.JwtClaims;

import com.acmeinsurance.auth.token.TokenService;
import com.acmeinsurance.common.web.ApiException;
import com.acmeinsurance.directory.DirectoryConfig;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;

/**
 * Sign-in through the token service (backend for frontend): /auth/login sends the
 * browser to its sign-in page, /auth/callback redeems the code with this app's
 * client secret and starts a session, /auth/logout ends it.
 */
@ApplicationScoped
@Named("authFlow")
public class AuthFlow {

    private static final Logger LOG = Logger.getLogger(AuthFlow.class);
    private static final String SIGNED_OUT = PATH_SIGNED_OUT + "?reason=";
    /** Session cookie attributes: the whole site, never readable by scripts, not sent on cross-site posts. */
    private static final String COOKIE_ATTRIBUTES = "; Path=/; HttpOnly; SameSite=Lax; Max-Age=";
    private static final String COOKIE_SECURE = "; Secure";
    private static final String COOKIE_SEPARATOR = ";";
    /** The session's local paths: "/people/agent", never "//host" or a full URL. */
    private static final String ROOT_PATH = "/";
    private static final String NETWORK_PATH_PREFIX = "//";
    /** /signed-out reason when the sign-in took too long or was not started here. */
    private static final String REASON_EXPIRED = "expired";

    private final OAuthClient oauth;
    private final SessionStore sessions;
    private final boolean secureCookie;

    public AuthFlow(final OAuthClient oauth, final SessionStore sessions, final DirectoryConfig cfg) {
        this.oauth = oauth;
        this.sessions = sessions;
        this.secureCookie = cfg.publicUrl().startsWith(SCHEME_HTTPS);
    }

    /** GET /auth/login?returnTo=/path */
    public void login(final Exchange ex) {
        final String verifier = sessions.randomId();
        final String state = sessions.begin(verifier, localPath(ex.getIn().getHeader(PARAM_RETURN_TO, String.class)),
                Instant.now());
        redirect(ex, oauth.authorizeUrl(state, challenge(verifier)));
    }

    /** GET /auth/callback?code=...&state=... (or ?error=...) */
    public void callback(final Exchange ex) {
        final String error = ex.getIn().getHeader(PARAM_ERROR, String.class);
        if (error != null) {
            redirect(ex, SIGNED_OUT + error);
            return;
        }
        final Instant now = Instant.now();
        final SessionStore.Pending pending = sessions.finish(ex.getIn().getHeader(PARAM_STATE, String.class), now);
        final String code = ex.getIn().getHeader(PARAM_CODE, String.class);
        if (pending == null || code == null) {
            redirect(ex, SIGNED_OUT + REASON_EXPIRED);
            return;
        }
        final OAuthClient.Tokens tokens;
        try {
            tokens = oauth.redeemCode(code, pending.verifier());
        } catch (final ApiException e) {
            LOG.infof("sign-in failed: %s", e.getMessage());
            redirect(ex, SIGNED_OUT + ERROR_SIGN_IN_FAILED);
            return;
        }
        final Set<String> permissions = permissions(tokens.claims());
        if (!permissions.contains(PERMISSION_VIEW)) {
            LOG.infof("sign-in refused: %s has no %s/%s", username(tokens.claims()), FEATURE_DIRECTORY,
                    PERMISSION_VIEW);
            redirect(ex, SIGNED_OUT + ERROR_FORBIDDEN);
            return;
        }
        final Session s = sessions.create(now);
        apply(s, tokens);
        ex.getMessage().setHeader(HEADER_SET_COOKIE, cookie(s.id(), sessions.sessionMax().toSeconds()));
        redirect(ex, pending.returnTo());
    }

    /** POST /auth/logout */
    public Map<String, Object> logout(final Exchange ex) {
        sessions.remove(sessionId(ex.getIn().getHeader(HEADER_COOKIE, String.class)));
        ex.getMessage().setHeader(HEADER_SET_COOKIE, cookie("", 0));
        return Map.of(JSON_SIGNED_OUT, true);
    }

    /** GET /api/me */
    public Map<String, Object> me(@ExchangeProperty(PROPERTY_SESSION) final Session s) {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put(JSON_USERNAME, s.username());
        m.put(JSON_NAME, s.name());
        m.put(JSON_PERMISSIONS, List.copyOf(new TreeSet<>(s.permissions())));
        return m;
    }

    /** Stores what a token response says about the user in the session. */
    public void apply(final Session s, final OAuthClient.Tokens tokens) {
        s.update(username(tokens.claims()), name(tokens.claims()), permissions(tokens.claims()),
                tokens.accessToken(), tokens.accessExpires(), tokens.refreshToken());
    }

    /** The user's permissions of the "directory" feature, from the features claim. */
    static Set<String> permissions(final JwtClaims claims) {
        return TokenService.permissions(claims, FEATURE_DIRECTORY);
    }

    /** The session ID in a Cookie header, or null. */
    static String sessionId(final String cookieHeader) {
        if (cookieHeader == null) {
            return null;
        }
        for (final String part : cookieHeader.split(COOKIE_SEPARATOR)) {
            final String p = part.trim();
            if (p.startsWith(SESSION_COOKIE + "=")) {
                final String v = p.substring(SESSION_COOKIE.length() + 1);
                return v.isEmpty() ? null : v;
            }
        }
        return null;
    }

    private String cookie(final String value, final long maxAge) {
        return SESSION_COOKIE + "=" + value + COOKIE_ATTRIBUTES + maxAge
                + (secureCookie ? COOKIE_SECURE : "");
    }

    private static String username(final JwtClaims c) {
        final Object v = c.getClaimValue(CLAIM_PREFERRED_USERNAME);
        return v == null ? String.valueOf(c.getClaimValue(CLAIM_SUBJECT)) : String.valueOf(v);
    }

    private static String name(final JwtClaims c) {
        final Object v = c.getClaimValue(CLAIM_NAME);
        if (v != null) {
            return String.valueOf(v);
        }
        final Object type = c.getClaimValue(CLAIM_USER_TYPE);
        return username(c) + (type == null ? "" : " (" + type + ")");
    }

    /** Only paths on this site ("/people/agent"), never "//host" or a full URL. */
    static String localPath(final String returnTo) {
        if (returnTo == null || !returnTo.startsWith(ROOT_PATH) || returnTo.startsWith(NETWORK_PATH_PREFIX)
                || returnTo.contains("\\") || returnTo.startsWith(PATH_APP_AUTH + ROOT_PATH)) {
            return ROOT_PATH;
        }
        return returnTo;
    }

    private static String challenge(final String verifier) {
        try {
            final byte[] hash = MessageDigest.getInstance(DIGEST_SHA_256)
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void redirect(final Exchange ex, final String location) {
        ex.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 302);
        ex.getMessage().setHeader(HEADER_LOCATION, location);
        ex.getMessage().setHeader(HEADER_CACHE_CONTROL, CACHE_NO_STORE);
        ex.getMessage().setBody("");
    }
}
