# OAuth: refresh token

Refresh tokens are stateless JWTs (`typ rt+jwt`, audience = the issuer), signed
with the same key as access tokens. They are issued only to **confidential**
clients that are allowed the `refresh_token` grant. Public clients such as
`demo-spa` never get one.

A refresh is not a blind re-sign: the service reads the user from LDAP again,
so locks, deactivation, resets, password changes and new grants take effect
at the next refresh (within 15 minutes, the access token lifetime).

```mermaid
sequenceDiagram
    autonumber
    participant App as Confidential client
    participant T as auth /oauth2/token
    participant K as SigningKeys
    participant L as OpenLDAP

    App->>T: POST grant_type=refresh_token, refresh_token + client credentials
    T->>L: findClient + bind as client entry
    alt bad client credentials
        T-->>App: 401 invalid_client
    else public client or refresh_token not allowed
        T-->>App: 400 unauthorized_client
    end

    T->>K: verify signature, typ rt+jwt, issuer, audience, expiry
    alt invalid or expired
        T-->>App: 400 invalid_grant "invalid refresh token"
    end
    T->>T: token client_id equals the authenticated client?
    alt issued to another client
        T-->>App: 400 invalid_grant
    end

    T->>L: findById(sub) as svc-auth
    L-->>T: user (status, pwdChangedTime, pwdReset, grants)
    alt missing, locked, deactivated or pwdReset
        T-->>App: 400 invalid_grant "user is not active, sign in again"
    else pwdChangedTime after the token's auth_time
        T-->>App: 400 invalid_grant "password changed since sign-in"
    else now is past auth_time + session-max (24 h)
        T-->>App: 400 invalid_grant "session expired, sign in again"
    else active
        T->>L: read current features, account paths, policies
        T->>K: sign new access token and refresh token (same auth_time)
        Note over T: refresh expiry = min(now + 8 h, auth_time + 24 h)
        T-->>App: 200 access_token, refresh_token
    end
```

## Lifetimes

| Setting | Default | Meaning |
|---|---|---|
| `auth.tokens.access-ttl` | 15 min | Access token lifetime |
| `auth.tokens.refresh-ttl` | 8 h | Refresh token lifetime (sliding) |
| `auth.tokens.session-max` | 24 h | Hard limit from the original sign-in (`auth_time`) |
| `auth.tokens.code-ttl` | 1 min | Authorization code lifetime |
| `auth.tokens.login-ttl` | 10 min | How long a login page stays valid |

Resource servers verify access tokens offline with the keys published at
`/oauth2/jwks`. The issuer's metadata is at `/.well-known/oauth-authorization-server`.
