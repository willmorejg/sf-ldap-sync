package com.acmeinsurance.directory.auth;

import java.time.Instant;
import java.util.Set;

/**
 * A signed-in user. The tokens stay here, on the server; the browser only holds
 * the random session ID in an HttpOnly cookie.
 */
public final class Session {

    private final String id;
    private final Instant created;
    private volatile String username;
    private volatile String name;
    private volatile Set<String> permissions;
    private volatile String accessToken;
    private volatile Instant accessExpires;
    private volatile String refreshToken;
    private volatile Instant lastSeen;

    public Session(final String id, final Instant created) {
        this.id = id;
        this.created = created;
        this.lastSeen = created;
    }

    /** Replaces the tokens and what they say about the user (after sign-in or a refresh). */
    public synchronized void update(final String user, final String displayName, final Set<String> granted,
                                    final String access, final Instant expires, final String refresh) {
        this.username = user;
        this.name = displayName;
        this.permissions = Set.copyOf(granted);
        this.accessToken = access;
        this.accessExpires = expires;
        if (refresh != null) {
            this.refreshToken = refresh;
        }
    }

    public String id() {
        return id;
    }

    public Instant created() {
        return created;
    }

    public String username() {
        return username;
    }

    public String name() {
        return name;
    }

    public Set<String> permissions() {
        return permissions;
    }

    public boolean can(final String permission) {
        return permissions != null && permissions.contains(permission);
    }

    public String accessToken() {
        return accessToken;
    }

    public Instant accessExpires() {
        return accessExpires;
    }

    public String refreshToken() {
        return refreshToken;
    }

    public Instant lastSeen() {
        return lastSeen;
    }

    public void touch(final Instant now) {
        this.lastSeen = now;
    }
}
