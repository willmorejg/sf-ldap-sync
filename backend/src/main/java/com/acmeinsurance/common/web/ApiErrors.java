package com.acmeinsurance.common.web;

import static com.acmeinsurance.common.Constants.HEADER_CONTENT_TYPE;
import static com.acmeinsurance.common.Constants.HEADER_WWW_AUTHENTICATE;
import static com.acmeinsurance.common.Constants.JSON_ERROR;
import static com.acmeinsurance.common.Constants.JSON_MESSAGE;
import static com.acmeinsurance.common.Constants.MEDIA_TYPE_JSON;

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.camel.Exchange;
import org.jboss.logging.Logger;

import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.ResultCode;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;

/**
 * Turns an exception from a Camel route (InsureDirectory's API, the sync trigger) into an HTTP
 * status and a JSON body {"error", "message"}. Called from routes/common.camel.yaml.
 */
@ApplicationScoped
@Named("apiErrors")
public class ApiErrors {

    private static final Logger LOG = Logger.getLogger(ApiErrors.class);
    /** Error code of an LDAP failure: ldap_ and the result code's name, e.g. ldap_entry_already_exists. */
    private static final String LDAP_ERROR_PREFIX = "ldap_";
    private static final String INTERNAL_ERROR = "internal_error";

    public Map<String, Object> handle(final Exchange ex) {
        final Throwable t = ex.getProperty(Exchange.EXCEPTION_CAUGHT, Throwable.class);
        final int status;
        final String error;
        final String message;
        String challenge = null;
        if (t instanceof final ApiException a) {
            status = a.status();
            error = a.error();
            message = a.getMessage();
            challenge = a.challenge();
        } else if (cause(t, LDAPException.class) instanceof final LDAPException l) {
            status = status(l.getResultCode());
            error = LDAP_ERROR_PREFIX + l.getResultCode().getName().replace(' ', '_');
            message = l.getDiagnosticMessage() == null ? l.getResultCode().getName() : l.getDiagnosticMessage();
            if (status >= 500) {
                LOG.warnf("LDAP error: %s", l.getExceptionMessage());
            }
        } else if (t instanceof IllegalArgumentException) {
            status = 400;
            error = ApiException.BAD_REQUEST;
            message = t.getMessage();
        } else {
            LOG.error("request failed", t);
            status = 500;
            error = INTERNAL_ERROR;
            message = "Something went wrong; the details are in the server log";
        }
        ex.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, status);
        ex.getMessage().setHeader(HEADER_CONTENT_TYPE, MEDIA_TYPE_JSON);
        if (challenge != null) {
            ex.getMessage().setHeader(HEADER_WWW_AUTHENTICATE, challenge);
        }
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put(JSON_ERROR, error);
        body.put(JSON_MESSAGE, message);
        return body;
    }

    static int status(final ResultCode rc) {
        if (ResultCode.NO_SUCH_OBJECT.equals(rc)) {
            return 404;
        }
        if (ResultCode.ENTRY_ALREADY_EXISTS.equals(rc) || ResultCode.NOT_ALLOWED_ON_NONLEAF.equals(rc)) {
            return 409;
        }
        if (ResultCode.CONSTRAINT_VIOLATION.equals(rc) || ResultCode.OBJECT_CLASS_VIOLATION.equals(rc)
                || ResultCode.INVALID_ATTRIBUTE_SYNTAX.equals(rc) || ResultCode.UNDEFINED_ATTRIBUTE_TYPE.equals(rc)
                || ResultCode.INVALID_DN_SYNTAX.equals(rc) || ResultCode.FILTER_ERROR.equals(rc)
                || ResultCode.ATTRIBUTE_OR_VALUE_EXISTS.equals(rc) || ResultCode.NO_SUCH_ATTRIBUTE.equals(rc)
                || ResultCode.NOT_ALLOWED_ON_RDN.equals(rc) || ResultCode.UNWILLING_TO_PERFORM.equals(rc)) {
            return 400;
        }
        if (ResultCode.INSUFFICIENT_ACCESS_RIGHTS.equals(rc)) {
            return 403;
        }
        return 502;
    }

    private static Throwable cause(final Throwable t, final Class<?> type) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (type.isInstance(c)) {
                return c;
            }
        }
        return null;
    }
}
