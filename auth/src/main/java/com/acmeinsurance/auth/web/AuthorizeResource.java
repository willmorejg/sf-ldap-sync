package com.acmeinsurance.auth.web;

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
@Path("/oauth2/authorize")
public class AuthorizeResource {

    private static final Logger LOG = Logger.getLogger(AuthorizeResource.class);
    private static final String INVALID = "Invalid username or password.";

    private final Directory directory;
    private final AuthorizationStore store;
    private final Template login;
    private final Template error;

    public AuthorizeResource(Directory directory, AuthorizationStore store,
                             @Location("login.html") Template login, @Location("error.html") Template error) {
        this.directory = directory;
        this.store = store;
        this.login = login;
        this.error = error;
    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    public Response authorize(@QueryParam("response_type") String responseType,
                              @QueryParam("client_id") String clientId,
                              @QueryParam("redirect_uri") String redirectUri,
                              @QueryParam("state") String state,
                              @QueryParam("code_challenge") String challenge,
                              @QueryParam("code_challenge_method") String challengeMethod) throws LDAPException {
        // Until the client and redirect URI are known good, errors are shown, never redirected.
        Client client = directory.findClient(clientId);
        if (client == null) {
            return errorPage("Unknown application.");
        }
        if (redirectUri == null || !client.redirectUris().contains(redirectUri)) {
            return errorPage("The application's redirect address is not registered.");
        }
        if (!"code".equals(responseType)) {
            return redirectError(redirectUri, state, "unsupported_response_type", null);
        }
        if (!client.allows("authorization_code")) {
            return redirectError(redirectUri, state, "unauthorized_client", null);
        }
        if (!"S256".equals(challengeMethod) || !Pkce.wellFormed(challenge)) {
            return redirectError(redirectUri, state, "invalid_request", "PKCE with S256 is required");
        }
        String id = store.newLogin(client.clientId(), redirectUri, state, challenge);
        if (id == null) {
            return redirectError(redirectUri, state, "temporarily_unavailable", null);
        }
        return page(client, id, null, null, false);
    }

    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public Response submit(@FormParam("request_id") String requestId,
                           @FormParam("username") String username,
                           @FormParam("password") String password,
                           @FormParam("new_password") String newPassword,
                           @FormParam("confirm_password") String confirmPassword) throws LDAPException {
        PendingLogin pending = store.login(requestId);
        if (pending == null) {
            return errorPage("This sign-in page has expired. Go back to the application and try again.");
        }
        Client client = directory.findClient(pending.clientId());
        if (client == null) {
            return errorPage("Unknown application.");
        }
        User user = directory.findByUsername(username);
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
            Directory.ChangeOutcome changed = directory.changePassword(user, password, newPassword);
            if (!changed.ok()) {
                return page(client, requestId, username, changed.message(), true);
            }
            LOG.infof("%s changed their password at sign-in", user.uid());
            password = newPassword;
        }

        BindOutcome outcome = directory.authenticate(user, password);
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
        User fresh = directory.findById(user.id());
        if (fresh == null || fresh.disabled() || fresh.passwordReset()) {
            return page(client, requestId, username, INVALID, false);
        }
        String code = store.newCode(pending, fresh.id(), Instant.now());
        if (code == null) {
            return redirectError(pending.redirectUri(), pending.state(), "temporarily_unavailable", null);
        }
        store.finishLogin(requestId);
        LOG.infof("authorization code: %s for client %s", fresh.uid(), client.clientId());
        UriBuilder ub = UriBuilder.fromUri(pending.redirectUri()).queryParam("code", code);
        if (pending.state() != null) {
            ub.queryParam("state", pending.state());
        }
        return Response.seeOther(ub.build()).cacheControl(noStore()).build();
    }

    private Response page(Client client, String requestId, String username, String message, boolean change) {
        String html = login.data("client", client.displayName())
                .data("requestId", requestId)
                .data("username", username == null ? "" : username)
                .data("message", message)
                .data("change", change)
                .render();
        return Response.ok(html, MediaType.TEXT_HTML_TYPE).cacheControl(noStore()).build();
    }

    private Response errorPage(String message) {
        return Response.status(400).type(MediaType.TEXT_HTML_TYPE).cacheControl(noStore())
                .entity(error.data("message", message).render()).build();
    }

    private static Response redirectError(String redirectUri, String state, String code, String description) {
        UriBuilder ub = UriBuilder.fromUri(redirectUri).queryParam("error", code);
        if (description != null) {
            ub.queryParam("error_description", description);
        }
        if (state != null) {
            ub.queryParam("state", state);
        }
        URI target = ub.build();
        return Response.seeOther(target).cacheControl(noStore()).build();
    }

    private static CacheControl noStore() {
        CacheControl cc = new CacheControl();
        cc.setNoStore(true);
        return cc;
    }
}
