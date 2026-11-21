package com.acmeinsurance.directory.auth;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.acmeinsurance.directory.DirectoryConfig;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Sign-in sessions and sign-ins in progress, in memory: a restart signs everyone
 * out, which only costs them one more sign-in.
 */
@ApplicationScoped
public class SessionStore {

    /** A sign-in waiting for its callback: the PKCE verifier and where to go afterwards. */
    public record Pending(String verifier, String returnTo, Instant created) {
    }

    private static final Duration PENDING_TTL = Duration.ofMinutes(10);

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final Duration sessionMax;
    private final Duration idleTimeout;

    public SessionStore(final DirectoryConfig cfg) {
        this.sessionMax = cfg.auth().sessionMax();
        this.idleTimeout = cfg.auth().idleTimeout();
    }

    /** @return the state value that identifies this sign-in */
    public String begin(final String verifier, final String returnTo, final Instant now) {
        purge(now);
        final String state = randomId();
        pending.put(state, new Pending(verifier, returnTo, now));
        return state;
    }

    /** The sign-in for this state, once; null if unknown or older than 10 minutes. */
    public Pending finish(final String state, final Instant now) {
        final Pending p = state == null ? null : pending.remove(state);
        return p == null || p.created().plus(PENDING_TTL).isBefore(now) ? null : p;
    }

    public Session create(final Instant now) {
        purge(now);
        final Session s = new Session(randomId(), now);
        sessions.put(s.id(), s);
        return s;
    }

    /** The live session with this ID, or null; an expired one is removed. */
    public Session find(final String id, final Instant now) {
        final Session s = id == null ? null : sessions.get(id);
        if (s == null) {
            return null;
        }
        if (expired(s, now)) {
            sessions.remove(id);
            return null;
        }
        s.touch(now);
        return s;
    }

    public void remove(final String id) {
        if (id != null) {
            sessions.remove(id);
        }
    }

    public Duration sessionMax() {
        return sessionMax;
    }

    public String randomId() {
        final byte[] b = new byte[32];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private boolean expired(final Session s, final Instant now) {
        return s.created().plus(sessionMax).isBefore(now) || s.lastSeen().plus(idleTimeout).isBefore(now);
    }

    private void purge(final Instant now) {
        sessions.values().removeIf(s -> expired(s, now));
        pending.values().removeIf(p -> p.created().plus(PENDING_TTL).isBefore(now));
    }
}
