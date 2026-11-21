package com.acmeinsurance.directory.service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import com.acmeinsurance.common.web.ApiException;

/** Entry IDs in URLs: the DN, base64url-encoded (DNs hold commas, spaces and slashes). */
public final class Ids {

    private Ids() {
    }

    public static String encode(final String dn) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(dn.getBytes(StandardCharsets.UTF_8));
    }

    public static String decode(final String id) {
        if (id == null || id.isBlank()) {
            throw ApiException.badRequest("missing entry id");
        }
        try {
            return new String(Base64.getUrlDecoder().decode(id), StandardCharsets.UTF_8);
        } catch (final IllegalArgumentException e) {
            throw ApiException.notFound("no such entry");
        }
    }
}
