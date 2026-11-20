package com.acmeinsurance.auth.web;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.Provider;

/** Headers for every response: no framing (clickjacking on the login page), no sniffing, no referrer. */
@Provider
public class SecurityHeaders implements ContainerResponseFilter {

    @Override
    public void filter(ContainerRequestContext req, ContainerResponseContext res) {
        MultivaluedMap<String, Object> h = res.getHeaders();
        h.putSingle("X-Frame-Options", "DENY");
        h.putSingle("X-Content-Type-Options", "nosniff");
        h.putSingle("Referrer-Policy", "no-referrer");
        h.putSingle("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'");
    }
}
