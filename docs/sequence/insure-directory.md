# InsureDirectory: sign-in and an API call

InsureDirectory is the directory maintenance app for customer service and developers.
The Angular app (`frontend/`) and its API (part of the backend, with Camel REST DSL
routes in `routes/directory-api.camel.yaml`) are served by the backend from one origin,
http://localhost:8080, together with the token service.

The API is the OAuth client, not the browser (a backend for frontend). It is the
confidential client `insure-directory`. Because the token service runs in the same app,
the API redeems the code and refreshes in-process through `TokenGrants`, the same code
behind `POST /oauth2/token`: it authenticates with its secret and gets the same checks.
The tokens never reach the browser, which holds only a random session ID in the HttpOnly
cookie `idir_session`. The code is in `backend/src/main/java/com/acmeinsurance/directory/auth/`
(`AuthFlow`, `Guard`, `OAuthClient`, `SessionStore`).

## Sign-in

```mermaid
sequenceDiagram
    autonumber
    actor U as User (browser)
    participant UI as Angular app
    participant D as InsureDirectory API
    participant A as token service (same app)
    participant L as OpenLDAP

    U->>UI: open /people/agent
    UI->>D: GET /api/me (no cookie)
    D-->>UI: 401 sign_in_required
    UI->>U: location = /auth/login?returnTo=/people/agent
    U->>D: GET /auth/login?returnTo=...
    D->>D: PKCE verifier and S256 challenge, state (pending 10 min), returnTo kept only if a local path
    D-->>U: 302 to /oauth2/authorize?client_id=insure-directory&redirect_uri=.../auth/callback (same origin)
    U->>A: sign in (the login page, forced password change, lockout: see oauth-authorization-code.md)
    A->>L: bind as the user (ppolicy decides)
    A-->>U: 303 to /auth/callback?code&state
    U->>D: GET /auth/callback?code&state
    D->>D: take the pending sign-in for state (once)
    D->>A: TokenGrants: authenticate insure-directory (secret), redeem code with code_verifier
    A-->>D: access token (RS256 JWT) and refresh token
    D->>A: TokenService.verifyAccess: signature, iss, aud, exp, typ at+jwt
    alt features.directory does not include view-entries
        D-->>U: 302 /signed-out?reason=forbidden (no session)
    else allowed
        D->>D: new session (tokens, user, permissions) in memory
        D-->>U: 302 to returnTo, Set-Cookie idir_session (HttpOnly, SameSite=Lax)
    end
```

Permissions come from the token's `features` claim, under the `directory` feature
(`cn=directory,ou=features`): `view-entries` to use the app, `edit-entries` to change
entries, and `run-queries` for the query builder. A grant of the feature itself grants
all three.

## An API call

```mermaid
sequenceDiagram
    autonumber
    participant UI as Angular app
    participant R as Camel route (directory-api.camel.yaml)
    participant G as guard bean
    participant A as token service (same app)
    participant S as entries / queries bean
    participant L as OpenLDAP
    participant Q as SQLite

    UI->>R: PUT /api/entries/agent/{id} with cookie and X-Requested-With: InsureDirectory
    R->>G: guard.edit
    G->>G: not GET: X-Requested-With present? (else 403 csrf)
    G->>G: session from cookie (else 401), idle 30 min, at most 8 h
    opt access token expires within 60 s
        G->>A: TokenGrants.refresh (as insure-directory, in-process)
        alt refused (locked, deactivated, reset, password changed)
            G-->>UI: 401: session removed, the app goes back to sign-in
        else
            A-->>G: new tokens: permissions re-read from the directory
        end
    end
    G->>G: has edit-entries? (else 403)
    R->>S: entries.update(session, kind, id, body)
    S->>L: read the entry as svc-directory
    S->>S: owner = acmeSource (salesforce, csv) or hand-made
    alt attribute owned by a sync
        S-->>UI: 409 maintained by Salesforce or the CSV feed
    else allowed
        S->>L: modify as svc-directory
        S->>Q: audit_log (who, what, attribute names, never values)
        S-->>UI: 200 the entry
    end
```

## What an entry's source allows

| Entry | Can change here |
|---|---|
| Hand-made (`acmeSource=manual` or none) | Everything except the RDN; can be deleted when it has no children |
| Salesforce agent | Feature grants (`acmeFeatureDN`) only |
| Salesforce agreement | Its underwriting company, unless `SF_MANAGES_UNDERWRITING_COMPANY=true` |
| CSV-fed person | Nothing; change the CSV feed |
| Any person | Lock, unlock (not when `acmeDeactivated`), reset password |

A password reset sets a random temporary password and `pwdReset: TRUE` in one
operation, so the person must change it at the next sign-in. The password is shown
once and never stored or logged.
