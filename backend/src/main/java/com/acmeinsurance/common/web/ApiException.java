package com.acmeinsurance.common.web;

/** An error answered with the given HTTP status and a JSON body {"error", "message"}. */
public final class ApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public static final String BAD_REQUEST = "bad_request";
    public static final String NOT_FOUND = "not_found";
    public static final String CONFLICT = "conflict";

    private final int status;
    private final String error;
    private final String challenge;

    public ApiException(final int status, final String error, final String message) {
        this(status, error, message, null);
    }

    /** @param challenge the WWW-Authenticate value of a 401 or 403, or null */
    public ApiException(final int status, final String error, final String message, final String challenge) {
        super(message);
        this.status = status;
        this.error = error;
        this.challenge = challenge;
    }

    public static ApiException badRequest(final String message) {
        return new ApiException(400, BAD_REQUEST, message);
    }

    public static ApiException notFound(final String message) {
        return new ApiException(404, NOT_FOUND, message);
    }

    public static ApiException conflict(final String message) {
        return new ApiException(409, CONFLICT, message);
    }

    public int status() {
        return status;
    }

    public String error() {
        return error;
    }

    public String challenge() {
        return challenge;
    }
}
