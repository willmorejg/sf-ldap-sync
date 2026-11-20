package com.acmeinsurance.auth.token;

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

    public static final String ACCESS_TYPE = "at+jwt";
    public static final String REFRESH_TYPE = "rt+jwt";

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
                .setExpectedType(true, REFRESH_TYPE)
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
            final String access = sign(accessClaims(user, client, authTime, now), ACCESS_TYPE);
            final String refresh = sign(refreshClaims(user, client, authTime, now, refreshExp), REFRESH_TYPE);
            final Map<String, Object> body = new LinkedHashMap<>();
            body.put("access_token", access);
            body.put("token_type", "Bearer");
            body.put("expires_in", cfg.tokens().accessTtl().toSeconds());
            if (issuesRefreshTokens(client)) {
                body.put("refresh_token", refresh);
                body.put("refresh_expires_in", Duration.between(now, refreshExp).toSeconds());
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
        return client.confidential() && client.allows("refresh_token");
    }

    /** Verifies a refresh token's signature, type, issuer, audience and expiry. */
    public Refresh verifyRefresh(final String token) {
        try {
            final JwtClaims c = refreshConsumer.processToClaims(token);
            return new Refresh(c.getSubject(), c.getStringClaimValue("client_id"),
                    Instant.ofEpochSecond(c.getClaimValue("auth_time", Long.class)));
        } catch (final InvalidJwtException | MalformedClaimException | RuntimeException e) {
            throw OAuthException.invalidGrant("invalid refresh token");
        }
    }

    JwtClaims accessClaims(final User user, final Client client, final Instant authTime,
                           final Instant now) throws LDAPException {
        final JwtClaims c = base(user, client, authTime, now, now.plus(cfg.tokens().accessTtl()));
        c.setAudience(cfg.audience());
        c.setStringClaim("preferred_username", user.uid());
        claim(c, "name", user.name());
        claim(c, "given_name", user.givenName());
        claim(c, "family_name", user.familyName());
        claim(c, "email", user.email());
        claim(c, "user_type", user.userType());
        final String type = user.userType();
        if (type != null && cfg.claims().featuresFor().contains(type)) {
            c.setClaim("features", directory.features(user));
        }
        if (type != null && cfg.claims().accountsFor().contains(type)) {
            c.setClaim("accounts", directory.accounts(user).asClaim());
        }
        if (type != null && cfg.claims().policiesFor().contains(type)) {
            c.setClaim("policies", user.policyNumbers());
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
        c.setStringClaim("client_id", client.clientId());
        c.setClaim("auth_time", authTime.getEpochSecond());
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
