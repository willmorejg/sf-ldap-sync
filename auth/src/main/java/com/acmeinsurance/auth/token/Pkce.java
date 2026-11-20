package com.acmeinsurance.auth.token;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.regex.Pattern;

/** PKCE (RFC 7636), S256 only. */
public final class Pkce {

    /** RFC 7636 section 4.1: 43 to 128 unreserved characters. Challenges have the same shape. */
    private static final Pattern SHAPE = Pattern.compile("[A-Za-z0-9._~-]{43,128}");

    private Pkce() {
    }

    public static boolean wellFormed(String value) {
        return value != null && SHAPE.matcher(value).matches();
    }

    public static String challenge(String verifier) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean matches(String verifier, String challenge) {
        if (!wellFormed(verifier) || challenge == null) {
            return false;
        }
        return MessageDigest.isEqual(challenge(verifier).getBytes(StandardCharsets.US_ASCII),
                challenge.getBytes(StandardCharsets.US_ASCII));
    }
}
