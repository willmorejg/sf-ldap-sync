# OAuth: password grant

For trusted server-side applications only: the password grant needs a
**confidential** client (an `acmeOAuthClient` with a `userPassword`). The client
authenticates with HTTP Basic (`client_secret_basic`) or form parameters
(`client_secret_post`), never both. Its secret is checked by binding as the client
entry. The code is in `TokenResource.password()`.

A user who must change their password can't get a token this way. They have to
sign in through the [authorization code flow](oauth-authorization-code.md), whose
login page handles the change.

```mermaid
sequenceDiagram
    autonumber
    participant App as Confidential client
    participant T as auth /oauth2/token
    participant L as OpenLDAP
    participant K as SigningKeys (RS256)

    App->>T: POST grant_type=password, username, password + client credentials
    T->>L: findClient(client_id) as svc-auth
    L-->>T: client (confidential = has userPassword)
    T->>L: bind as client entry with client_secret
    alt unknown client or wrong secret
        T-->>App: 401 invalid_client
    else client may not use password, or is public
        T-->>App: 400 unauthorized_client
    end

    T->>L: findByUsername as svc-auth
    alt unknown, deactivated or permanently locked
        T-->>App: 400 invalid_grant "invalid username or password"
    end
    T->>L: bind as user + ppolicy request control
    L-->>T: result + ppolicy response
    alt MUST_CHANGE
        T-->>App: 400 invalid_grant "password change required"
    else EXPIRED
        T-->>App: 400 invalid_grant "password expired"
    else INVALID or locked out
        T-->>App: 400 invalid_grant "invalid username or password"
    else OK
        T->>L: findById (re-read: the bind may clear a lockout timestamp)
        T->>L: read features, account paths, policies (by user type)
        T->>K: sign access token (at+jwt, 15 min) and refresh token (rt+jwt)
        T-->>App: 200 access_token, refresh_token, expires_in (Cache-Control: no-store)
    end
```
