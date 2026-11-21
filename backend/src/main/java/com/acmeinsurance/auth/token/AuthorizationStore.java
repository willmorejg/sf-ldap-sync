package com.acmeinsurance.auth.token;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.acmeinsurance.auth.AuthConfig;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Short-lived state for the authorization code flow, held in memory: login pages
 * waiting for a password, and issued codes waiting to be exchanged. In memory
 * means one instance only; running several behind a load balancer would need a
 * shared store (or sticky sessions plus shared codes).
 */
@ApplicationScoped
public class AuthorizationStore {

    /** Most login pages and codes held at once; beyond this new ones are refused. */
    static final int MAX_ENTRIES = 10_000;

    public record PendingLogin(String clientId, String redirectUri, String state, String codeChallenge,
                               Instant expires) {
    }

    public record Code(String clientId, String redirectUri, String codeChallenge, String userId,
                       Instant authTime, Instant expires) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AuthConfig cfg;
    private final Map<String, PendingLogin> logins = new ConcurrentHashMap<>();
    private final Map<String, Code> codes = new ConcurrentHashMap<>();

    public AuthorizationStore(final AuthConfig cfg) {
        this.cfg = cfg;
    }

    /** Stores a login request and returns its id, or null if too many are pending. */
    public String newLogin(final String clientId, final String redirectUri, final String state,
                           final String codeChallenge) {
        purge();
        if (logins.size() >= MAX_ENTRIES) {
            return null;
        }
        final String id = token();
        logins.put(id, new PendingLogin(clientId, redirectUri, state, codeChallenge,
                Instant.now().plus(cfg.tokens().loginTtl())));
        return id;
    }

    /** The pending login, if it exists and has not expired (it stays until finished, for retries). */
    public PendingLogin login(final String id) {
        final PendingLogin p = id == null ? null : logins.get(id);
        return p == null || p.expires().isBefore(Instant.now()) ? null : p;
    }

    public void finishLogin(final String id) {
        logins.remove(id);
    }

    /** Issues a one-time authorization code, or null if too many are outstanding. */
    public String newCode(final PendingLogin p, final String userId, final Instant authTime) {
        purge();
        if (codes.size() >= MAX_ENTRIES) {
            return null;
        }
        final String code = token();
        codes.put(code, new Code(p.clientId(), p.redirectUri(), p.codeChallenge(), userId, authTime,
                Instant.now().plus(cfg.tokens().codeTtl())));
        return code;
    }

    /** Removes and returns a code; a code can only ever be taken once. */
    public Code takeCode(final String code) {
        final Code c = code == null ? null : codes.remove(code);
        return c == null || c.expires().isBefore(Instant.now()) ? null : c;
    }

    private void purge() {
        final Instant now = Instant.now();
        logins.values().removeIf(p -> p.expires().isBefore(now));
        codes.values().removeIf(c -> c.expires().isBefore(now));
    }

    private static String token() {
        final byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
