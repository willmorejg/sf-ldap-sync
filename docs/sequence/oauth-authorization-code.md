# OAuth: authorization code with PKCE

The flow for browser and single-page applications (for example the public sample
client `demo-spa`). It follows RFC 6749 section 4.1 with PKCE (RFC 7636), and only
`S256` is accepted. There is no consent screen, because every client is an Acme
Insurance application. The code is in `auth/src/main/java/com/acmeinsurance/auth/web/AuthorizeResource.java`
and `TokenResource.java`.

The service never checks a password itself. It binds to OpenLDAP as the user and
reads the ppolicy response control, so the password policy decides lockout,
expiry and forced changes.

## Sign-in and code exchange

```mermaid
sequenceDiagram
    autonumber
    actor U as User (browser)
    participant App as Client app
    participant A as auth /oauth2/authorize
    participant St as AuthorizationStore (memory)
    participant T as auth /oauth2/token
    participant L as OpenLDAP

    App->>App: create code_verifier, code_challenge = S256(verifier)
    App->>U: redirect to /oauth2/authorize?response_type=code&client_id&redirect_uri&state&code_challenge
    U->>A: GET /oauth2/authorize
    A->>L: findClient(client_id) as svc-auth
    L-->>A: acmeOAuthClient (redirect URIs, grant types)
    alt unknown client or unregistered redirect_uri
        A-->>U: error page (never redirect to an unchecked URI)
    else bad response_type, grant not allowed, or no S256 PKCE
        A-->>U: 303 to redirect_uri?error=...&state
    else valid
        A->>St: newLogin(client, redirect_uri, state, challenge) (10 min)
        A-->>U: login page with request_id
    end

    U->>A: POST username, password, request_id
    A->>St: login(request_id)
    A->>L: findByUsername as svc-auth
    alt unknown, deactivated or permanently locked
        A-->>U: login page: "Invalid username or password."
    end
    A->>L: bind as user + ppolicy request control
    L-->>A: result + ppolicy response
    alt OK
        A->>L: findById (re-read after bind)
        A->>St: newCode(pending, entryUUID, auth_time) (1 min, single use)
        A->>St: finishLogin(request_id)
        A-->>U: 303 to redirect_uri?code&state
    else MUST_CHANGE (pwdReset)
        A-->>U: login page with the change-password form
        Note over U,A: continued in "Forced password change" below
    else EXPIRED
        A-->>U: "Your password has expired. Ask an administrator to reset it."
    else INVALID (wrong password or lockout)
        A-->>U: login page: "Invalid username or password."
    end

    U->>App: GET redirect_uri?code&state
    App->>App: check state
    App->>T: POST grant_type=authorization_code, code, redirect_uri, code_verifier, client_id
    T->>L: findClient, check secret only if confidential
    T->>St: takeCode(code) (removed on first use)
    alt missing, expired, other client, redirect_uri differs, or verifier mismatch
        T-->>App: 400 invalid_grant
    else valid
        T->>L: findById(sub) as svc-auth
        alt locked, deactivated or pwdReset
            T-->>App: 400 invalid_grant
        else active
            T->>L: read features, account paths, policies (by user type)
            T-->>App: 200 access_token (+ refresh_token for confidential clients)
        end
    end
```

## Forced password change

After an administrator resets a password (with `pwdReset: TRUE`), ppolicy only
allows the user to change it. The login page offers that change, made as the user
with the Password Modify extended operation, so the password policy still checks
the new password's quality and history.

```mermaid
sequenceDiagram
    autonumber
    actor U as User (browser)
    participant A as auth /oauth2/authorize
    participant L as OpenLDAP

    U->>A: POST username, password, new_password, confirm_password, request_id
    alt passwords don't match, or new equals current
        A-->>U: change form with message
    else
        A->>L: bind as user with current password
        A->>L: Password Modify (old, new) + ppolicy control
        alt rejected (too short, in history, ...)
            L-->>A: constraint violation + ppolicy error
            A-->>U: change form with the policy's reason
        else accepted
            L-->>A: success (pwdReset cleared, pwdChangedTime set)
            A->>L: bind as user with the new password
            L-->>A: OK
            A-->>U: 303 to redirect_uri?code&state
        end
    end
```

## Token contents

| Claim | Present for |
|---|---|
| `sub` (entryUUID), `preferred_username`, `name`, `email`, `user_type`, `auth_time`, `client_id` | everyone |
| `features` (feature → permissions) | agent, internal, other |
| `accounts` (agency-rooted paths of held grants) | agent, internal, other |
| `policies` (policy numbers) | member, other |

The user types for each claim are set by `auth.claims.*` in
`auth/src/main/resources/application.properties`. See also
[oauth-refresh-token.md](oauth-refresh-token.md) and
[oauth-password-grant.md](oauth-password-grant.md).
