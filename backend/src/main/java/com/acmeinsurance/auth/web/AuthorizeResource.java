package com.acmeinsurance.auth.web;

import static com.acmeinsurance.common.Constants.ERROR_INVALID_REQUEST;
import static com.acmeinsurance.common.Constants.ERROR_TEMPORARILY_UNAVAILABLE;
import static com.acmeinsurance.common.Constants.ERROR_UNAUTHORIZED_CLIENT;
import static com.acmeinsurance.common.Constants.ERROR_UNSUPPORTED_RESPONSE_TYPE;
import static com.acmeinsurance.common.Constants.GRANT_TYPE_AUTHORIZATION_CODE;
import static com.acmeinsurance.common.Constants.PARAM_CLIENT_ID;
import static com.acmeinsurance.common.Constants.PARAM_CODE;
import static com.acmeinsurance.common.Constants.PARAM_CODE_CHALLENGE;
import static com.acmeinsurance.common.Constants.PARAM_CODE_CHALLENGE_METHOD;
import static com.acmeinsurance.common.Constants.PARAM_ERROR;
import static com.acmeinsurance.common.Constants.PARAM_ERROR_DESCRIPTION;
import static com.acmeinsurance.common.Constants.PARAM_PASSWORD;
import static com.acmeinsurance.common.Constants.PARAM_REDIRECT_URI;
import static com.acmeinsurance.common.Constants.PARAM_RESPONSE_TYPE;
import static com.acmeinsurance.common.Constants.PARAM_STATE;
import static com.acmeinsurance.common.Constants.PARAM_USERNAME;
import static com.acmeinsurance.common.Constants.PATH_AUTHORIZE;
import static com.acmeinsurance.common.Constants.PKCE_METHOD_S256;
import static com.acmeinsurance.common.Constants.RESPONSE_TYPE_CODE;

import java.net.URI;
import java.time.Instant;

import org.jboss.logging.Logger;

import com.acmeinsurance.auth.ldap.Directory;
import com.acmeinsurance.auth.ldap.Directory.BindOutcome;
import com.acmeinsurance.auth.ldap.Directory.Client;
import com.acmeinsurance.auth.ldap.Directory.User;
import com.acmeinsurance.auth.token.AuthorizationStore;
import com.acmeinsurance.auth.token.AuthorizationStore.PendingLogin;
import com.acmeinsurance.auth.token.Pkce;
import com.unboundid.ldap.sdk.LDAPException;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.enums.ParameterIn;

import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.CacheControl;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;

/**
 * The authorization endpoint and its login page (authorization code flow with
 * PKCE, RFC 6749 section 4.1 and RFC 7636). There is no consent screen: every
 * client is a Acme Insurance application. A user whose password was reset by an
 * administrator changes it here before getting a code.
 */
@Path(PATH_AUTHORIZE)
@Tag(name = "OAuth 2.0")
public final class AuthorizeResource {

    private static final Logger LOG = Logger.getLogger(AuthorizeResource.class);
    /** Qute templates (templates/). Their data keys are named where they are filled in. */
    private static final String TEMPLATE_LOGIN = "login.html";
    private static final String TEMPLATE_ERROR = "error.html";
    /** Fields of the login page's form that are not OAuth parameters. */
    private static final String FORM_REQUEST_ID = "request_id";
    private static final String FORM_NEW_PASSWORD = "new_password";
    private static final String FORM_CONFIRM_PASSWORD = "confirm_password";
    private static final String INVALID = "Invalid username or password.";

    private final Directory directory;
    private final AuthorizationStore store;
    private final Template login;
    private final Template error;

    public AuthorizeResource(final Directory directory, final AuthorizationStore store,
                             @Location(TEMPLATE_LOGIN) final Template login,
                             @Location(TEMPLATE_ERROR) final Template error) {
        this.directory = directory;
        this.store = store;
        this.login = login;
        this.error = error;
    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    @Operation(summary = "Start a sign-in (authorization code with PKCE)",
            description = "Open this in the user's browser, not from code. Shows the login page; after a "
                    + "successful sign-in the browser is redirected to redirect_uri with code and state. Errors "
                    + "about the request are sent to redirect_uri as error and state; an unknown client or "
                    + "unregistered redirect_uri shows an error page instead.")
    @APIResponse(responseCode = "200", description = "Login page", content = @Content(mediaType = MediaType.TEXT_HTML))
    @APIResponse(responseCode = "303", description = "Redirect to redirect_uri with error and state")
    @APIResponse(responseCode = "400", description = "Unknown client or unregistered redirect_uri (error page)",
            content = @Content(mediaType = MediaType.TEXT_HTML))
    public Response authorize(
            @Parameter(in = ParameterIn.QUERY, required = true, description = "Must be code")
            @QueryParam(PARAM_RESPONSE_TYPE) final String responseType,
            @Parameter(in = ParameterIn.QUERY, required = true)
            @QueryParam(PARAM_CLIENT_ID) final String clientId,
            @Parameter(in = ParameterIn.QUERY, required = true, description = "One of the client's registered redirect URIs")
            @QueryParam(PARAM_REDIRECT_URI) final String redirectUri,
            @Parameter(in = ParameterIn.QUERY, description = "Opaque value returned unchanged; use it against CSRF")
            @QueryParam(PARAM_STATE) final String state,
            @Parameter(in = ParameterIn.QUERY, required = true, description = "BASE64URL(SHA-256(code_verifier))")
            @QueryParam(PARAM_CODE_CHALLENGE) final String challenge,
            @Parameter(in = ParameterIn.QUERY, required = true, description = "Must be S256")
            @QueryParam(PARAM_CODE_CHALLENGE_METHOD) final String challengeMethod) throws LDAPException {
        // Until the client and redirect URI are known good, errors are shown, never redirected.
        final Client client = directory.findClient(clientId);
        if (client == null) {
            return errorPage("Unknown application.");
        }
        if (redirectUri == null || !client.redirectUris().contains(redirectUri)) {
            return errorPage("The application's redirect address is not registered.");
        }
        if (!RESPONSE_TYPE_CODE.equals(responseType)) {
            return redirectError(redirectUri, state, ERROR_UNSUPPORTED_RESPONSE_TYPE, null);
        }
        if (!client.allows(GRANT_TYPE_AUTHORIZATION_CODE)) {
            return redirectError(redirectUri, state, ERROR_UNAUTHORIZED_CLIENT, null);
        }
        if (!PKCE_METHOD_S256.equals(challengeMethod) || !Pkce.wellFormed(challenge)) {
            return redirectError(redirectUri, state, ERROR_INVALID_REQUEST, "PKCE with S256 is required");
        }
        final String id = store.newLogin(client.clientId(), redirectUri, state, challenge);
        if (id == null) {
            return redirectError(redirectUri, state, ERROR_TEMPORARILY_UNAVAILABLE, null);
        }
        return page(client, id, null, null, false);
    }

    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Operation(summary = "Login page form (browser only)",
            description = "Posted by the login page, including its change-password form. Not an API: clients "
                    + "must not collect passwords and post them here.")
    @APIResponse(responseCode = "200", description = "Login page again, with a message",
            content = @Content(mediaType = MediaType.TEXT_HTML))
    @APIResponse(responseCode = "303", description = "Signed in: redirect to redirect_uri with code and state")
    public Response submit(@FormParam(FORM_REQUEST_ID) final String requestId,
                           @FormParam(PARAM_USERNAME) final String username,
                           @FormParam(PARAM_PASSWORD) String password,
                           @FormParam(FORM_NEW_PASSWORD) final String newPassword,
                           @FormParam(FORM_CONFIRM_PASSWORD) final String confirmPassword) throws LDAPException {
        final PendingLogin pending = store.login(requestId);
        if (pending == null) {
            return errorPage("This sign-in page has expired. Go back to the application and try again.");
        }
        final Client client = directory.findClient(pending.clientId());
        if (client == null) {
            return errorPage("Unknown application.");
        }
        final User user = directory.findByUsername(username);
        if (user == null || user.blocked()) {
            return page(client, requestId, username, INVALID, false);
        }

        if (newPassword != null) { // the change-password form
            if (!newPassword.equals(confirmPassword)) {
                return page(client, requestId, username, "The new passwords do not match.", true);
            }
            if (newPassword.equals(password)) {
                return page(client, requestId, username, "Choose a password different from the current one.", true);
            }
            final Directory.ChangeOutcome changed = directory.changePassword(user, password, newPassword);
            if (!changed.ok()) {
                return page(client, requestId, username, changed.message(), true);
            }
            LOG.infof("%s changed their password at sign-in", user.uid());
            password = newPassword;
        }

        final BindOutcome outcome = directory.authenticate(user, password);
        switch (outcome) {
            case OK -> { }
            case MUST_CHANGE -> {
                return page(client, requestId, username,
                        "Your password was reset by an administrator. Choose a new password to continue.", true);
            }
            case EXPIRED -> {
                return page(client, requestId, username,
                        "Your password has expired. Ask an administrator to reset it.", false);
            }
            default -> {
                return page(client, requestId, username, INVALID, false);
            }
        }
        final User fresh = directory.findById(user.id());
        if (fresh == null || fresh.disabled() || fresh.passwordReset()) {
            return page(client, requestId, username, INVALID, false);
        }
        final String code = store.newCode(pending, fresh.id(), Instant.now());
        if (code == null) {
            return redirectError(pending.redirectUri(), pending.state(), ERROR_TEMPORARILY_UNAVAILABLE, null);
        }
        store.finishLogin(requestId);
        LOG.infof("authorization code: %s for client %s", fresh.uid(), client.clientId());
        final UriBuilder ub = UriBuilder.fromUri(pending.redirectUri()).queryParam(PARAM_CODE, code);
        if (pending.state() != null) {
            ub.queryParam(PARAM_STATE, pending.state());
        }
        return Response.seeOther(ub.build()).cacheControl(noStore()).build();
    }

    private Response page(final Client client, final String requestId, final String username, final String message,
                          final boolean change) {
        final String html = login.data("client", client.displayName())
                .data("requestId", requestId)
                .data("username", username == null ? "" : username)
                .data("message", message)
                .data("change", change)
                .render();
        return Response.ok(html, MediaType.TEXT_HTML_TYPE).cacheControl(noStore()).build();
    }

    private Response errorPage(final String message) {
        return Response.status(400).type(MediaType.TEXT_HTML_TYPE).cacheControl(noStore())
                .entity(error.data("message", message).render()).build();
    }

    private static Response redirectError(final String redirectUri, final String state, final String code,
                                          final String description) {
        final UriBuilder ub = UriBuilder.fromUri(redirectUri).queryParam(PARAM_ERROR, code);
        if (description != null) {
            ub.queryParam(PARAM_ERROR_DESCRIPTION, description);
        }
        if (state != null) {
            ub.queryParam(PARAM_STATE, state);
        }
        final URI target = ub.build();
        return Response.seeOther(target).cacheControl(noStore()).build();
    }

    private static CacheControl noStore() {
        final CacheControl cc = new CacheControl();
        cc.setNoStore(true);
        return cc;
    }
}
