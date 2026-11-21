package com.acmeinsurance.directory.auth;

import static com.acmeinsurance.common.Constants.ERROR_CSRF;
import static com.acmeinsurance.common.Constants.ERROR_FORBIDDEN;
import static com.acmeinsurance.common.Constants.ERROR_SIGN_IN_REQUIRED;
import static com.acmeinsurance.common.Constants.HEADER_COOKIE;
import static com.acmeinsurance.common.Constants.HEADER_REQUESTED_WITH;
import static com.acmeinsurance.common.Constants.HTTP_GET;
import static com.acmeinsurance.common.Constants.PERMISSION_EDIT;
import static com.acmeinsurance.common.Constants.PERMISSION_QUERY;
import static com.acmeinsurance.common.Constants.PERMISSION_VIEW;
import static com.acmeinsurance.common.Constants.PROPERTY_SESSION;
import static com.acmeinsurance.common.Constants.REQUESTED_WITH_VALUE;

import java.time.Duration;
import java.time.Instant;

import org.apache.camel.Exchange;

import com.acmeinsurance.common.web.ApiException;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;

/**
 * The first step of every /api route: finds the session from the cookie, refreshes
 * its access token when it is about to expire (the token service re-reads the
 * directory, so a locked or deactivated user, or one whose grants were removed,
 * loses access within one access-token lifetime), checks the permission, and puts
 * the session on the exchange.
 *
 * <p>Requests other than GET must carry X-Requested-With: InsureDirectory. A form
 * or link on another site cannot set it, and the cookie is SameSite=Lax as well.
 */
@ApplicationScoped
@Named("guard")
public class Guard {

    private static final Duration REFRESH_BEFORE = Duration.ofSeconds(60);

    private final SessionStore sessions;
    private final OAuthClient oauth;
    private final AuthFlow flow;

    public Guard(final SessionStore sessions, final OAuthClient oauth, final AuthFlow flow) {
        this.sessions = sessions;
        this.oauth = oauth;
        this.flow = flow;
    }

    public void view(final Exchange ex) {
        require(ex, PERMISSION_VIEW);
    }

    public void edit(final Exchange ex) {
        require(ex, PERMISSION_EDIT);
    }

    public void query(final Exchange ex) {
        require(ex, PERMISSION_QUERY);
    }

    void require(final Exchange ex, final String permission) {
        final String method = ex.getIn().getHeader(Exchange.HTTP_METHOD, String.class);
        if (method != null && !HTTP_GET.equalsIgnoreCase(method)
                && !REQUESTED_WITH_VALUE.equals(ex.getIn().getHeader(HEADER_REQUESTED_WITH, String.class))) {
            throw new ApiException(403, ERROR_CSRF, "missing " + HEADER_REQUESTED_WITH + " header");
        }
        final Instant now = Instant.now();
        final Session s = sessions.find(AuthFlow.sessionId(ex.getIn().getHeader(HEADER_COOKIE, String.class)), now);
        if (s == null) {
            throw new ApiException(401, ERROR_SIGN_IN_REQUIRED, "Please sign in");
        }
        if (s.accessExpires().minus(REFRESH_BEFORE).isBefore(now)) {
            try {
                flow.apply(s, oauth.refresh(s.refreshToken()));
            } catch (final ApiException e) {
                sessions.remove(s.id());
                throw new ApiException(401, ERROR_SIGN_IN_REQUIRED, "Your session has ended; please sign in again");
            }
        }
        if (!s.can(permission)) {
            throw new ApiException(403, ERROR_FORBIDDEN, "You do not have the " + permission + " permission");
        }
        ex.setProperty(PROPERTY_SESSION, s);
    }
}
