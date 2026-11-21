package com.acmeinsurance.common.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.unboundid.ldap.sdk.ResultCode;

class WebFiltersTest {

    @Test
    void onlyAppRoutesFallBackToIndexHtml() {
        assertTrue(WebFilters.isAppPath("/people/agent"));
        assertTrue(WebFilters.isAppPath("/accounts"));
        assertTrue(WebFilters.isAppPath("/apiary"));
        assertFalse(WebFilters.isAppPath("/"));
        assertFalse(WebFilters.isAppPath("/main-ABC123.js"));
        assertFalse(WebFilters.isAppPath("/api/me"));
        assertFalse(WebFilters.isAppPath("/auth/login"));
        assertFalse(WebFilters.isAppPath("/q/health"));
        assertFalse(WebFilters.isAppPath("/openapi"));
        assertFalse(WebFilters.isAppPath("/oauth2/authorize"));
        assertFalse(WebFilters.isAppPath("/.well-known/oauth-authorization-server"));
        assertFalse(WebFilters.isAppPath("/sync/salesforce"));
    }

    @Test
    void ldapResultCodesMapToHttpStatuses() {
        assertEquals(404, ApiErrors.status(ResultCode.NO_SUCH_OBJECT));
        assertEquals(409, ApiErrors.status(ResultCode.ENTRY_ALREADY_EXISTS));
        assertEquals(409, ApiErrors.status(ResultCode.NOT_ALLOWED_ON_NONLEAF));
        assertEquals(400, ApiErrors.status(ResultCode.CONSTRAINT_VIOLATION));
        assertEquals(403, ApiErrors.status(ResultCode.INSUFFICIENT_ACCESS_RIGHTS));
        assertEquals(502, ApiErrors.status(ResultCode.SERVER_DOWN));
    }
}
