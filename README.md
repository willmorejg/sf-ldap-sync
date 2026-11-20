# Acme Insurance LDAP sync and token service

[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=willmorejg_sf-ldap-sync&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=willmorejg_sf-ldap-sync)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=willmorejg_sf-ldap-sync&metric=coverage)](https://sonarcloud.io/summary/overall?id=willmorejg_sf-ldap-sync)
[![Maintainability Rating](https://sonarcloud.io/api/project_badges/measure?project=willmorejg_sf-ldap-sync&metric=sqale_rating)](https://sonarcloud.io/summary/overall?id=willmorejg_sf-ldap-sync)
[![Reliability Rating](https://sonarcloud.io/api/project_badges/measure?project=willmorejg_sf-ldap-sync&metric=reliability_rating)](https://sonarcloud.io/summary/overall?id=willmorejg_sf-ldap-sync)
[![Security Rating](https://sonarcloud.io/api/project_badges/measure?project=willmorejg_sf-ldap-sync&metric=security_rating)](https://sonarcloud.io/summary/overall?id=willmorejg_sf-ldap-sync)

Keeps the Acme Insurance OpenLDAP directory in step with its sources, and issues
OAuth 2.0 JWT access tokens from it:

| Directory branch | Source | How |
|---|---|---|
| `ou=agencies,ou=accounts` (agency > agreement > book tree) | Salesforce Account record types | Camel route, nightly and on change |
| `ou=agents,ou=people` (brokers and agents) | Salesforce User | Camel route, nightly and on change |
| `ou=internal`, `ou=members`, `ou=other` | Not in Salesforce | CSV drop folder for now |
| `ou=clients,ou=accounts` | Internal client IDs | By hand (phpLDAPadmin / ldapadd) |

It is a Podman Compose project with three containers:

- **openldap**: OpenLDAP 2.6 built from the Nov 2026 schema design, plus small sync and auth add-ons. The same container serves phpLDAPadmin (nginx + php-fpm) at http://localhost:8090/.
- **sync**: a Camel Quarkus service. Its YAML routes decide *when* a sync runs; Java code decides *what* to write.
- **auth**: a Quarkus OAuth 2.0 token service. It checks passwords against the directory and puts an agent's features and accounts in the JWT (see [Token service](#token-service-auth)).

Further documentation, including sequence diagrams of every flow, is in [docs/](docs/index.md).

```
.
├── compose.yaml, .env.example
├── docs/
│   ├── index.md                      documentation index + system overview
│   ├── LDAP_Schema_Definition.md     schema and user-type requirements
│   └── sequence/                     Mermaid sequence diagrams (sync, CSV, OAuth flows, startup)
├── openldap/
│   ├── Containerfile, entrypoint.sh   first start builds cn=config + tree from ldif/
│   ├── smoke-test.sh                  checks the overlays/ACLs the sync relies on
│   ├── phpldapadmin/                  config.php, nginx site, php.ini for the web UI
│   └── ldif/
│       ├── 00-config-global.ldif      modules
│       ├── 01-acmeinsurance-schema.ldif   acmeUser, acmeAccountAccess, acmeMemberAccess, acmeAccount...
│       ├── 02-acmeinsurance-sync-schema.ldif  NEW: acmeSource, acmeDeactivated, acmeSyncManaged
│       ├── 03-acmeinsurance-auth-schema.ldif  NEW: acmeFeature, acmePermission, acmeFeatureDN, acmeOAuthClient
│       ├── 10-config-db.ldif          mdb, indexes, limits, ACLs, ppolicy/unique/refint/constraint
│       ├── 20-dit.ldif                OUs, service accounts, password policies
│       ├── 30-sample-clients.ldif     two made-up client IDs (LOAD_SAMPLES=true)
│       ├── 31-sample-agencies.ldif    AG0042 sample tree + one agent (LOAD_SAMPLE_AGENCIES=true; not with the sync)
│       ├── 32-sample-features.ldif    quoting > read-quotes, write-quotes
│       ├── 33-sample-oauth-public.ldif        demo-spa (PKCE) client (LOAD_SAMPLES=true)
│       └── 34-sample-oauth-confidential.ldif  demo-app client (LOAD_SAMPLES=true + OAUTH_DEMO_CLIENT_SECRET)
├── sync/
│   ├── build.gradle, settings.gradle, gradle.properties, gradlew, Containerfile
│   └── src/main/
│       ├── resources/application.properties     all settings + Salesforce field mapping
│       ├── resources/routes/*.camel.yaml        schedule, HTTP trigger, CDC, CSV intake
│       └── java/com/acmeinsurance/ldapsync/
│           ├── core/        pure logic: tree planning, diffs, mappers, safety guard (unit-tested)
│           ├── ldap/        UnboundID adapter + PersonWriter
│           ├── salesforce/  query reader + SalesforceSyncService
│           └── csv/         CsvIntakeService
├── auth/
│   ├── build.gradle, settings.gradle, gradle.properties, gradlew, Containerfile
│   └── src/main/
│       ├── resources/application.properties, templates/   settings, login page
│       ├── resources/META-INF/openapi.yaml       base OpenAPI document (info, schemas)
│       └── java/com/acmeinsurance/auth/
│           ├── ldap/Directory.java   user/client lookup, binds with ppolicy controls, grant resolution
│           ├── token/                signing keys + JWKS, token building, codes, PKCE
│           └── web/                  /oauth2/authorize, /oauth2/token, metadata, JWKS
└── samples/csv/{internal,members,other}/   example files for the inbox
```

## Quick start

```sh
cp .env.example .env          # set passwords; SF_ENABLED=false until Salesforce values are in
podman compose up --build -d

# Smoke test, run inside the openldap container (it has ldap-utils)
set -a; . ./.env; set +a
podman cp openldap/smoke-test.sh sf-ldap-sync_openldap_1:/tmp/smoke-test.sh
podman exec -e SVC_SYNC_PASSWORD -e SVC_AUTH_PASSWORD sf-ldap-sync_openldap_1 sh /tmp/smoke-test.sh

# Load the sample people (needs LOAD_SAMPLES=true for the client IDs)
cp -r samples/csv/* inbox/
podman compose logs -f sync

# Salesforce: dry run first (DRY_RUN=true in .env logs writes instead of making them)
curl -X POST localhost:8080/sync/salesforce     # run now, returns the report
curl localhost:8080/sync/salesforce             # last report
```

When a dry-run report looks right, set `DRY_RUN=false` and restart `sync`.

**API documentation (OpenAPI).**

| Service | Spec | Swagger UI |
|---|---|---|
| sync | http://localhost:8080/openapi (`/openapi.yaml` for YAML) | http://localhost:8080/q/swagger-ui/ |
| auth | http://localhost:8081/q/openapi (`?format=json` for JSON) | dev mode only (`./gradlew quarkusDev`) |

- **sync:** Camel generates the spec at runtime from the REST DSL in `routes/salesforce-sync.camel.yaml`
  (`camel-quarkus-openapi-java`), so it always matches the routes. The operations are always listed; with
  `SF_ENABLED=false` they answer 503 `{"status": "disabled: ..."}`. The title and description are in that file's `restConfiguration`.
  `SYNC_API_HOST` sets the server shown in the spec (default `localhost:8080`).
- **auth:** generated from the annotations in `auth/.../web/`, merged with the base document in
  `auth/src/main/resources/META-INF/openapi.yaml` (info, client authentication, schemas).
- Swagger UI is left out of the auth image on purpose: it is the production login service, and OAuth
  clients find its endpoints through the RFC 8414 metadata anyway.
- `OPENAPI_ENABLED=false` stops auth serving its spec. The sync spec is always on (loopback only).

**phpLDAPadmin** runs at http://localhost:8090/ (localhost only). Sign in as `cn=admin,dc=acmeinsurance,dc=com` with
`LDAP_ADMIN_PASSWORD`, or as any other entry (svc-sync, a user), which gets that entry's ACL rights.
- **Passwords:** set them with the hash type left as `clear`. slapd then hashes them with Argon2 and
  applies the password policy. When the rootdn sets a password, slapd also sets `pwdReset`, so the user
  must change it at the next sign-in on the auth login page.
- **Synced entries:** entries with a `acmeSource` belong to a sync, and the next run overwrites hand edits
  to them. Hand-made entries, features, OAuth clients and `ou=clients` are safe to edit here.
- **Turning it off:** set `PHPLDAPADMIN_ENABLED=false`. nginx and php-fpm run beside slapd, which stays
  PID 1, so a web UI failure is logged and never stops the directory.

Add `-e LDAP_ADMIN_PASSWORD` to the smoke-test `podman exec` to include the OAuth client checks.

`DRY_RUN` covers every write, including the CSV intake. With `SF_ENABLED=false` the Salesforce routes are not created at all (`/sync/salesforce` answers 503 with a `disabled` status), so the app runs on CSV files alone.

## Salesforce setup

1. Create a connected app (or external client app) with **Enable Client Credentials Flow**. Set its **Run As** user to an integration user.
2. That user needs read access to Account (including RecordType) and User. It also needs AccountContactRelation if you use the entitlement query.
3. Put the My Domain URL, consumer key and secret in `.env`.
4. Optional, for near-real-time updates:
   - Enable Change Data Capture for Account and User (and AccountContactRelation).
   - Set `sync.cdc.enabled=true`.
   - Events only mark the sync as dirty. A run follows within `sync.cdc.debounce-ms`, so a burst of edits costs one run.

## Mapping assumptions to check

All of these are settings in `application.properties`, not code:

- **Levels:** agencies, agreements and books are Account record types named `Agency`, `Agreement` and `Book`, linked by `ParentId`.
- **cn:** the business identifier (AG0042, AGR-0042-01, BK-000311) is in `AccountNumber`. Point `sync.accounts.cn-field` at the real field. A record with an empty value falls back to its Salesforce ID, with a warning.
- **Which users:** every Salesforce User is a broker or agent. Exclude admin and integration users in `sync.agents.soql`, for example by Profile.
- **Agent's agency:** agents are partner (Experience Cloud) users, so the agency is `Contact.AccountId`. If agents are standard users, point `sync.agents.agency-field` at the User field that holds the agency.
- **Agreement and book grants:** these come from `AccountContactRelation` if that feature is on (commented out by default). If Acme Insurance keeps them in a custom junction object, change `entitlement-soql` and its two field names.
- **Login name:** `uid` is the Salesforce Username. `FederationIdentifier` is an alternative.

## How it behaves

- **Ownership.**
  - Every entry the sync writes carries `acmeSource`: `salesforce`, or `csv` for all three CSV feeds. The
    older `csv-internal`, `csv-members` and `csv-other` values still count as `csv`, and are rewritten on the
    next update.
  - A source only changes entries it owns.
  - Entries made by hand have no `acmeSource`, or `acmeSource: manual`, and are never touched.
    `sync.safety.adopt-unowned=true` lets a source take over a matching hand-made entry.
- **Full comparison every run.** Each run reads everything and writes only the differences, so running it repeatedly is safe.
  - Renamed or re-parented accounts are moved with modrdn.
  - The `refint` overlay rewrites every agent's grants to match.
- **Removals.**
  - Accounts gone from Salesforce are deleted, children first. `refint` then drops them from users' grants.
  - Agents no longer returned are **locked**, not deleted: `pwdAccountLockedTime=000001010000Z` plus `acmeDeactivated`.
  - They unlock automatically if they come back.
- **Safety.**
  - A query that returns nothing never removes anything.
  - One run may remove at most `sync.safety.max-remove-percent` (10%) of a source's entries. If it would remove more, it stops and reports why.
- **Grants.**
  - The Salesforce sync manages only an agent's grants under `ou=agencies`. Client grants (`ou=clients`) are left alone.
  - CSV feeds own all grants on the entries they own.
- **Passwords are never written.** Agents get `pwdPolicySubentry=cn=agents` and internal users `cn=internal` when created. Members and other users use the default policy.
- **Password resets must change at next login (all three policies).** OpenLDAP 2.6 only sets `pwdReset` automatically when the person resetting has `manage` access, which the rootdn has and svc-sync deliberately does not, so svc-sync resets still have to meet the length, quality and history rules. Anything that resets passwords through svc-sync must also set `pwdReset: TRUE` in the same modify (or on add). Until the user changes their password, every operation except bind, WhoAmI and password change is refused.
- **Records that can't be placed are reported, not guessed at.** This covers a book under an agency, a missing parent, or two siblings with the same cn. Their existing entries are left in place.

## Token service (auth)

An OAuth 2.0 authorization server on port 8081. It has no database: users, grants and
registered clients are all read from the directory, and tokens are stateless JWTs.

| Endpoint | |
|---|---|
| `GET /.well-known/oauth-authorization-server` | RFC 8414 metadata |
| `GET /oauth2/jwks` | public signing keys (RS256) |
| `GET/POST /oauth2/authorize` | login page; authorization code flow, PKCE (S256) required |
| `POST /oauth2/token` | `authorization_code`, `password`, `refresh_token` grants |

**Signing in.** The user is found by `uid` under `ou=people`, and the password is checked by binding as
the user, so OpenLDAP's password policy decides: lockout, expiry and reset all apply. A user whose password
was reset by an administrator (`pwdReset`) is shown a change-password form on the login page. The password
grant answers `invalid_grant` / `password change required` instead. Deactivated or permanently locked users
are refused before the bind.

**Access token** (`typ: at+jwt`, RFC 9068 shape). `sub` is the user's `entryUUID`, which survives a uid
rename. `aud` is `AUTH_AUDIENCE` (default `acmeinsurance`). It carries `preferred_username`, `name`,
`given_name`, `family_name`, `email`, `user_type` and `client_id`. The grant claims depend on the user type
(`auth.claims.*`, following `docs/LDAP_Schema_Definition.md`):

| User type | `features` | `accounts` | `policies` |
|---|---|---|---|
| agent | yes (occasional) | yes (primary) | no |
| internal | yes (primary) | yes (optional scope) | no |
| member | no | no | yes |
| other | yes | yes | yes |

```json
"features": { "quoting": ["read-quotes", "write-quotes"], "csr": [] },
"policies": ["POL-2024-98765"],
"accounts": {
  "agencies":   ["AG0042"],
  "agreements": ["AG0042/AGR-0042-01"],
  "books":      ["AG0042/AGR-0042-02/BK-000340"],
  "clients":    ["100234"]
}
```

Grants are listed as held, not expanded. Agreements and books are paths from the agency down, so a resource
server allows book `AG0042/AGR-0042-01/BK-000311` when the path equals a grant or starts with a grant
followed by `/`. A feature grant lists every permission under that feature; a feature with no permissions
(a plain role such as `csr`) appears with an empty list.

**Refresh.** A refresh re-reads the user, so grant changes show up in the next access token. It is refused if
the user has since been deactivated or locked, has a pending reset, or changed their password after signing
in. It is also refused for a different client. Only confidential clients get refresh tokens; a public client
signs in again (authorization code) when its access token expires. Each refresh issues a new refresh token (`auth.tokens.refresh-ttl`,
8 hours), but never beyond `session-max` (24 hours) after the password was entered. Access tokens last
15 minutes. Tokens cannot be revoked individually; locking the user or resetting their password ends the
session at the next refresh.

**Features.** Create `cn=<feature>,ou=features` (`acmeFeature`) with `acmePermission` children. The user needs
`acmeFeatureAccess` (CSV internal and other users, and synced agents, get it automatically) and a `acmeFeatureDN`
naming either the feature (all of its permissions,
including ones added later) or one permission. As with accounts, the constraint overlay refuses a DN that is
not a feature or permission, and refint cleans up deletes and renames. Users cannot change their own grants.
The sync never touches `acmeFeatureDN`.

**Registering a client** (as the rootdn). A `acmeOAuthClient` under `ou=oauth-clients,ou=services`, where `cn` is
the `client_id`:

- `acmeRedirectUri`: exact-match redirect URIs.
- `acmeGrantType`: `authorization_code`, `password` and/or `refresh_token`.
- A confidential client also has a `userPassword` (its secret, at least 32 characters, hashed by slapd) and
  `pwdPolicySubentry: cn=oauth-clients,ou=policies,...`, a policy with no lockout.
- A public client (browser or mobile app) has no secret, must use PKCE, and uses `authorization_code` only:
  it never gets a refresh token, even if `refresh_token` is listed.
- The password grant is only allowed for confidential clients.

```sh
curl -u demo-app:$OAUTH_DEMO_CLIENT_SECRET -d grant_type=password \
     -d username=sample.agent --data-urlencode password=... localhost:8081/oauth2/token
```

**Keys.** On first start a 3072-bit RSA key is generated into the `auth-keys` volume. To rotate:
1. Move the old key aside.
2. List its public key in `auth.signing.previous-public-key-files`.
3. Restart.
Tokens signed with the old key verify until they expire.

**Limits.**
- Authorization codes and login pages are held in memory, so run one instance.
- There is no consent screen; every client is assumed to be a Acme Insurance app.
- There is no rate limiting beyond ppolicy lockout.
- It must sit behind HTTPS in production; set `AUTH_ISSUER` to the public URL.
- Browser apps on another origin need CORS (commented out in `application.properties`).

## CSV format (internal, members, other)

Put the file in `inbox/internal/`, `inbox/members/` or `inbox/other/`. The folder decides the user type.

- `uid` is required.
- Optional columns: `givenName sn cn mail telephoneNumber mobile title displayName employeeNumber departmentNumber active accounts policyNumbers features`.
- A column that is present replaces the attribute; an empty cell removes it. A column that is absent is left alone.
- Separate multiple values with `|`.
- `accounts` takes `client:100234`, `sf:<Salesforce account ID>` or a full DN.
- `features` takes `claims` (a feature, with all its permissions), `claims/view-claims` (one permission) or a
  full DN. The directory refuses features that do not exist.
- Which grants each feed accepts:
  - internal: `features` and `accounts`
  - members: `policyNumbers` only
  - other: all three
  A row with a grant its user type cannot hold fails.
- `active=false` locks the entry. Rows are never deleted.
- Files with any failed row go to `.failed/` (good rows are still applied). Re-dropping a fixed file is safe.

## Applying the sync add-on to an existing server

If OpenLDAP is already running from the original LDIFs:

1. `ldapadd -Y EXTERNAL -H ldapi:/// -f openldap/ldif/02-acmeinsurance-sync-schema.ldif`
2. Add to the mdb database:
   - the `olcLimits` lines for `svc-sync`/`svc-auth`. Without them, OpenLDAP caps paged searches at 1000 entries and the sync would see only part of the tree.
   - the `acmeSource` index.
   - the people-side `acmeSalesforceId` unique URI.
3. Add `acmeSalesforceId,acmeSource,acmeDeactivated,pwdAccountLockedTime` to the ACL rule that gives `svc-sync` write access to entitlement attributes.

A server built before 2026-11-20 has the provisioning account as `cn=svc-idm`. Run
`openldap/migrations/2026-11-20-rename-svc-idm-to-svc-sync.sh` inside the container (instructions at its top).
It renames the entry, keeping its password, and rewrites the ACLs and limits. Then rename `SVC_IDM_PASSWORD`
to `SVC_SYNC_PASSWORD` in `.env`.

For the auth add-on, also:

1. `ldapadd -Y EXTERNAL -H ldapi:/// -f openldap/ldif/03-acmeinsurance-auth-schema.ldif`
2. Add `ou=features` and `ou=oauth-clients,ou=services`, and the `cn=oauth-clients` password policy, from `20-dit.ldif`.
3. On the mdb database:
   - add `acmeFeatureDN` to the refint attributes
   - add the `acmeFeatureDN` constraint
   - add the `acmeFeatureDN,entryUUID` index
   - add `acmeFeatureDN` to the entitlement-attribute ACL
   - add the `ou=features` ACL
   - add the client-secret ACL, which must come before the `userPassword` rule

See `10-config-db.ldif` for the exact values.

## What has been tested

- **OpenLDAP config:** loaded into a real OpenLDAP 2.6.10.
- **Smoke test:** `smoke-test.sh` passes 25/25 (last run 2026-11-20, slapd 2.6.10 in the openldap image). It covers:
  - uniqueness
  - the existence constraints on account and feature grants
  - the `acmeUserType` value constraint
  - `refint` on subtree move and delete, for accounts and features
  - lock/unlock and password reset via ppolicy
  - the ACLs (users cannot change their own grants or user type)
  - OAuth clients: a confidential client binds, there is no lockout, svc-auth can tell confidential from public clients, and it cannot read secrets
- **Token service:** `cd auth && ./gradlew build` runs 16 tests against an in-memory directory. They cover claims for each user type, the password, code+PKCE and refresh grants, client authentication, and the reset/change-password flow (ppolicy is simulated). The service was also run against real slapd 2.6.10 (2026-11-19). It checked the real ppolicy controls (reset forces a change, a too-short new password is rejected), the claims for `sample.agent`, and that a ppolicy lockout blocks both sign-in and refresh.
- **Paged searches:** with the limits above, paging returns more than 1000 entries.
- **Build and tests:** `cd sync && ./gradlew build` (Gradle 9.9.1 wrapper, Groovy DSL, needs JDK 25; Gradle can't run on JDK 21 for this build). It runs 45 tests: unit tests for `core/`, plus `PersonWriter`, `SalesforceSyncService` and `CsvIntakeService` integration tests against an in-memory LDAP server loaded with the real schema from `openldap/ldif`. The container build skips tests, because its context is `sync/` only.
- **App startup:** with `SF_ENABLED=false` the sync container runs on CSV intake alone (the 4 sample people load). With `SF_ENABLED=true` and no credentials it also starts and serves `/sync/salesforce` (a sync run without credentials has not been tried).
- **OpenAPI (2026-11-20):** sync serves the Camel-generated spec at `/openapi` and Swagger UI shows it, with both operations listed whatever `SF_ENABLED` is. Checked both ways: `SF_ENABLED=true` returns the report, `false` returns 503 JSON. auth serves `/q/openapi`; `OPENAPI_ENABLED=false` turns it off.
- **To confirm against a real org:**
  - `queryMore` paging takes the `nextRecordsUrl` from the message body.
  - The CDC endpoint syntax `salesforce:subscribe:data/ChangeEvents` is right for the Camel version in use.

## Next steps

- Replace the CSV feeds with real sources: Entra ID / Microsoft Graph for staff, and the policy system for members (`camel-jt400` or `camel-sql` against IBM i).
- Replace the placeholder OID arc `1.3.6.1.4.1.99999` with Acme Insurance's IANA Private Enterprise Number.
- Turn on TLS (`ldaps://` is supported by the sync; the server side needs certificates).
