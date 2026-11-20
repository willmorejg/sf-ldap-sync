package com.acmeinsurance.auth.token;

import static com.acmeinsurance.auth.Constants.CLAIM_ACCOUNTS;
import static com.acmeinsurance.auth.Constants.CLAIM_AUTH_TIME;
import static com.acmeinsurance.auth.Constants.CLAIM_CLIENT_ID;
import static com.acmeinsurance.auth.Constants.CLAIM_EMAIL;
import static com.acmeinsurance.auth.Constants.CLAIM_FAMILY_NAME;
import static com.acmeinsurance.auth.Constants.CLAIM_FEATURES;
import static com.acmeinsurance.auth.Constants.CLAIM_GIVEN_NAME;
import static com.acmeinsurance.auth.Constants.CLAIM_NAME;
import static com.acmeinsurance.auth.Constants.CLAIM_POLICIES;
import static com.acmeinsurance.auth.Constants.CLAIM_PREFERRED_USERNAME;
import static com.acmeinsurance.auth.Constants.CLAIM_USER_TYPE;
import static com.acmeinsurance.auth.Constants.GRANT_TYPE_REFRESH_TOKEN;
import static com.acmeinsurance.auth.Constants.PARAM_ACCESS_TOKEN;
import static com.acmeinsurance.auth.Constants.PARAM_EXPIRES_IN;
import static com.acmeinsurance.auth.Constants.PARAM_REFRESH_EXPIRES_IN;
import static com.acmeinsurance.auth.Constants.PARAM_REFRESH_TOKEN;
import static com.acmeinsurance.auth.Constants.PARAM_TOKEN_TYPE;
import static com.acmeinsurance.auth.Constants.TOKEN_TYPE_BEARER;
import static com.acmeinsurance.auth.Constants.TYP_ACCESS_TOKEN;
import static com.acmeinsurance.auth.Constants.TYP_REFRESH_TOKEN;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.MalformedClaimException;
import org.jose4j.jwt.NumericDate;
import org.jose4j.jwt.consumer.InvalidJwtException;
import org.jose4j.jwt.consumer.JwtConsumer;
import org.jose4j.jwt.consumer.JwtConsumerBuilder;
import org.jose4j.keys.resolvers.JwksVerificationKeyResolver;
import org.jose4j.lang.JoseException;

import com.acmeinsurance.auth.AuthConfig;
import com.acmeinsurance.auth.OAuthException;
import com.acmeinsurance.auth.ldap.Directory;
import com.acmeinsurance.auth.ldap.Directory.Client;
import com.acmeinsurance.auth.ldap.Directory.User;
import com.unboundid.ldap.sdk.LDAPException;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Builds the token response: a signed JWT access token (RFC 9068 shape) and a
 * signed refresh token. Both are stateless; a refresh re-reads the user from the
 * directory, so grant changes, locks and password resets take effect then.
 */
@ApplicationScoped
public class TokenService {


    private final AuthConfig cfg;
    private final SigningKeys keys;
    private final Directory directory;
    private final JwtConsumer refreshConsumer;

    public TokenService(final AuthConfig cfg, final SigningKeys keys, final Directory directory) {
        this.cfg = cfg;
        this.keys = keys;
        this.directory = directory;
        this.refreshConsumer = new JwtConsumerBuilder()
                .setRequireExpirationTime()
                .setRequireIssuedAt()
                .setRequireSubject()
                .setRequireJwtId()
                .setAllowedClockSkewInSeconds(30)
                .setExpectedIssuer(cfg.issuer())
                .setExpectedAudience(cfg.issuer())
                .setExpectedType(true, TYP_REFRESH_TOKEN)
                .setVerificationKeyResolver(new JwksVerificationKeyResolver(keys.verificationKeys()))
                .setJwsAlgorithmConstraints(org.jose4j.jwa.AlgorithmConstraints.ConstraintType.PERMIT,
                        AlgorithmIdentifiers.RSA_USING_SHA256)
                .build();
    }

    /** What a valid refresh token says. */
    public record Refresh(String subject, String clientId, Instant authTime) {
    }

    /** Issues an access and refresh token for a user who authenticated at {@code authTime}. */
    public Map<String, Object> issue(final User user, final Client client, final Instant authTime) {
        final Instant now = Instant.now();
        final Instant refreshExp = min(now.plus(cfg.tokens().refreshTtl()), authTime.plus(cfg.tokens().sessionMax()));
        if (!refreshExp.isAfter(now)) {
            throw OAuthException.invalidGrant("session expired; sign in again");
        }
        try {
            final String access = sign(accessClaims(user, client, authTime, now), TYP_ACCESS_TOKEN);
            final String refresh = sign(refreshClaims(user, client, authTime, now, refreshExp), TYP_REFRESH_TOKEN);
            final Map<String, Object> body = new LinkedHashMap<>();
            body.put(PARAM_ACCESS_TOKEN, access);
            body.put(PARAM_TOKEN_TYPE, TOKEN_TYPE_BEARER);
            body.put(PARAM_EXPIRES_IN, cfg.tokens().accessTtl().toSeconds());
            if (issuesRefreshTokens(client)) {
                body.put(PARAM_REFRESH_TOKEN, refresh);
                body.put(PARAM_REFRESH_EXPIRES_IN, Duration.between(now, refreshExp).toSeconds());
            }
            return body;
        } catch (final LDAPException e) {
            throw new IllegalStateException("directory unavailable", e);
        } catch (final JoseException e) {
            throw new IllegalStateException("cannot sign token", e);
        }
    }

    /**
     * Refresh tokens go to confidential clients only: a public client (browser or
     * mobile app) uses the authorization code grant alone, and signs in again.
     */
    public static boolean issuesRefreshTokens(final Client client) {
        return client.confidential() && client.allows(GRANT_TYPE_REFRESH_TOKEN);
    }

    /** Verifies a refresh token's signature, type, issuer, audience and expiry. */
    public Refresh verifyRefresh(final String token) {
        try {
            final JwtClaims c = refreshConsumer.processToClaims(token);
            return new Refresh(c.getSubject(), c.getStringClaimValue(CLAIM_CLIENT_ID),
                    Instant.ofEpochSecond(c.getClaimValue(CLAIM_AUTH_TIME, Long.class)));
        } catch (final InvalidJwtException | MalformedClaimException | RuntimeException e) {
            throw OAuthException.invalidGrant("invalid refresh token");
        }
    }

    JwtClaims accessClaims(final User user, final Client client, final Instant authTime,
                           final Instant now) throws LDAPException {
        final JwtClaims c = base(user, client, authTime, now, now.plus(cfg.tokens().accessTtl()));
        c.setAudience(cfg.audience());
        c.setStringClaim(CLAIM_PREFERRED_USERNAME, user.uid());
        claim(c, CLAIM_NAME, user.name());
        claim(c, CLAIM_GIVEN_NAME, user.givenName());
        claim(c, CLAIM_FAMILY_NAME, user.familyName());
        claim(c, CLAIM_EMAIL, user.email());
        claim(c, CLAIM_USER_TYPE, user.userType());
        final String type = user.userType();
        if (type != null && cfg.claims().featuresFor().contains(type)) {
            c.setClaim(CLAIM_FEATURES, directory.features(user));
        }
        if (type != null && cfg.claims().accountsFor().contains(type)) {
            c.setClaim(CLAIM_ACCOUNTS, directory.accounts(user).asClaim());
        }
        if (type != null && cfg.claims().policiesFor().contains(type)) {
            c.setClaim(CLAIM_POLICIES, user.policyNumbers());
        }
        return c;
    }

    private JwtClaims refreshClaims(final User user, final Client client, final Instant authTime, final Instant now,
                                    final Instant exp) {
        final JwtClaims c = base(user, client, authTime, now, exp);
        c.setAudience(cfg.issuer()); // only this service accepts it
        return c;
    }

    private JwtClaims base(final User user, final Client client, final Instant authTime, final Instant now,
                           final Instant exp) {
        final JwtClaims c = new JwtClaims();
        c.setIssuer(cfg.issuer());
        c.setSubject(user.id());
        c.setIssuedAt(NumericDate.fromSeconds(now.getEpochSecond()));
        c.setNotBefore(NumericDate.fromSeconds(now.getEpochSecond()));
        c.setExpirationTime(NumericDate.fromSeconds(exp.getEpochSecond()));
        c.setJwtId(UUID.randomUUID().toString());
        c.setStringClaim(CLAIM_CLIENT_ID, client.clientId());
        c.setClaim(CLAIM_AUTH_TIME, authTime.getEpochSecond());
        return c;
    }

    private String sign(final JwtClaims claims, final String type) throws JoseException {
        final JsonWebSignature jws = new JsonWebSignature();
        jws.setPayload(claims.toJson());
        jws.setKey(keys.current().getPrivateKey());
        jws.setKeyIdHeaderValue(keys.current().getKeyId());
        jws.setAlgorithmHeaderValue(AlgorithmIdentifiers.RSA_USING_SHA256);
        jws.setHeader("typ", type);
        return jws.getCompactSerialization();
    }

    private static void claim(final JwtClaims c, final String name, final String value) {
        if (value != null) {
            c.setStringClaim(name, value);
        }
    }

    private static Instant min(final Instant a, final Instant b) {
        return a.isBefore(b) ? a : b;
    }

}
