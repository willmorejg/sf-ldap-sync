package com.acmeinsurance.common.web;

import static com.acmeinsurance.common.Constants.HEADER_CONTENT_TYPE_OPTIONS;
import static com.acmeinsurance.common.Constants.HEADER_CSP;
import static com.acmeinsurance.common.Constants.HEADER_FRAME_OPTIONS;
import static com.acmeinsurance.common.Constants.HEADER_REFERRER_POLICY;
import static com.acmeinsurance.common.Constants.PATH_API;
import static com.acmeinsurance.common.Constants.PATH_APP_AUTH;
import static com.acmeinsurance.common.Constants.PATH_INDEX_HTML;
import static com.acmeinsurance.common.Constants.PATH_OAUTH2;
import static com.acmeinsurance.common.Constants.PATH_OPENAPI;
import static com.acmeinsurance.common.Constants.PATH_QUARKUS;
import static com.acmeinsurance.common.Constants.PATH_SYNC;
import static com.acmeinsurance.common.Constants.PATH_WELL_KNOWN;

import java.util.List;

import io.quarkus.vertx.web.RouteFilter;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Singleton;

/**
 * Every response of the app, whichever module serves it: security headers, and the
 * Angular app for its own deep links (a GET for /people/agent, with no dot and not a
 * server path, is served /index.html, and the app's router takes it from there).
 */
@Singleton
public final class WebFilters {

    /** The token service's pages: nothing but inline styles, never framed (clickjacking on the login page). */
    static final String CSP_TOKEN_SERVICE = "default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'";
    /** The Angular app and its API. */
    static final String CSP_APP = "default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; "
            + "frame-ancestors 'none'; base-uri 'self'; form-action 'self'";
    private static final String NO_SNIFF = "nosniff";
    private static final String DENY = "DENY";
    /** Authorization redirects carry codes; never leak them in a Referer. */
    private static final String NO_REFERRER = "no-referrer";
    private static final List<String> TOKEN_SERVICE_PATHS = List.of(PATH_OAUTH2, PATH_WELL_KNOWN);
    private static final List<String> SERVER_PATHS =
            List.of(PATH_API, PATH_APP_AUTH, PATH_OAUTH2, PATH_WELL_KNOWN, PATH_SYNC, PATH_QUARKUS, PATH_OPENAPI);
    private static final int HEADERS_ORDER = 100;
    private static final int SPA_ORDER = 90;

    @RouteFilter(HEADERS_ORDER)
    void headers(final RoutingContext rc) {
        rc.response().headers()
                .set(HEADER_CSP, under(rc.normalizedPath(), TOKEN_SERVICE_PATHS) ? CSP_TOKEN_SERVICE : CSP_APP)
                .set(HEADER_CONTENT_TYPE_OPTIONS, NO_SNIFF)
                .set(HEADER_REFERRER_POLICY, NO_REFERRER)
                .set(HEADER_FRAME_OPTIONS, DENY);
        rc.next();
    }

    @RouteFilter(SPA_ORDER)
    void spa(final RoutingContext rc) {
        if (HttpMethod.GET.equals(rc.request().method()) && isAppPath(rc.normalizedPath())) {
            rc.reroute(PATH_INDEX_HTML);
            return;
        }
        rc.next();
    }

    static boolean isAppPath(final String path) {
        if (path == null || "/".equals(path) || path.lastIndexOf('.') > path.lastIndexOf('/')) {
            return false;
        }
        return !under(path, SERVER_PATHS);
    }

    static boolean under(final String path, final List<String> prefixes) {
        if (path == null) {
            return false;
        }
        for (final String p : prefixes) {
            if (path.equals(p) || path.startsWith(p + "/")) {
                return true;
            }
        }
        return false;
    }
}
