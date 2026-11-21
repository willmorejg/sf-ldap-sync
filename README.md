# Acme Insurance LDAP sync and token service

<center><sm><b>SonarQube:</b></sm></center>

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

It is a Podman Compose project with two containers:

- **openldap**: OpenLDAP 2.6 built from the Nov 2026 schema design, plus small sync and auth add-ons. The same container serves phpLDAPadmin (nginx + php-fpm) at http://localhost:8090/.
- **backend**: one Camel Quarkus app on http://localhost:8080/ with three parts, each binding to the directory as its
  own service account:
  - the **sync** (svc-sync). Its YAML routes decide *when* a sync runs; Java code decides *what* to write.
  - the **token service** (svc-auth), an OAuth 2.0 authorization server. It checks passwords against the directory and
    puts a user's features and accounts in the JWT (see [Token service](#token-service-auth)).
  - **InsureDirectory** (svc-directory), a directory maintenance app for customer service and developers: its API,
    and the Angular app from `frontend/` (see [InsureDirectory](#insuredirectory)).

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
│   ├── migrations/                    one-off scripts that bring an existing server up to date
│   ├── phpldapadmin/                  config.php, nginx site, php.ini for the web UI
│   └── ldif/
│       ├── 00-config-global.ldif      modules
│       ├── 01-acmeinsurance-schema.ldif   acmeUser, acmeAccountAccess, acmeMemberAccess, acmeAccount...,
│       │                                  acmeUnderwritingCompany, acmeUnderwritingCompanyDN (agreements)
│       ├── 02-acmeinsurance-sync-schema.ldif  NEW: acmeSource, acmeDeactivated, acmeSyncManaged
│       ├── 03-acmeinsurance-auth-schema.ldif  NEW: acmeFeature, acmePermission, acmeFeatureDN, acmeOAuthClient
│       ├── 10-config-db.ldif          mdb, indexes, limits, ACLs, ppolicy/unique/refint/constraint
│       ├── 20-dit.ldif                OUs, service accounts, password policies
│       ├── 30-sample-clients.ldif     two made-up client IDs (LOAD_SAMPLES=true)
│       ├── 31-sample-agencies.ldif    AG0042 sample tree + one agent (LOAD_SAMPLE_AGENCIES=true; not with the sync)
│       ├── 32-sample-features.ldif    quoting > read-quotes, write-quotes
│       ├── 33-sample-oauth-public.ldif        demo-spa (PKCE) client (LOAD_SAMPLES=true)
│       ├── 34-sample-oauth-confidential.ldif  demo-app client (LOAD_SAMPLES=true + OAUTH_DEMO_CLIENT_SECRET)
│       ├── 35-sample-underwriting-companies.ldif  two companies (LOAD_SAMPLES or LOAD_SAMPLE_AGENCIES)
│       ├── 36-insure-directory.ldif   the "directory" feature: view-entries, edit-entries, run-queries
│       ├── 37-oauth-insure-directory.ldif  the insure-directory OAuth client (DIRECTORY_CLIENT_SECRET)
│       └── 38-sync-feature.ldif       the "sync" feature: run-sync (the HTTP trigger)
├── backend/                         the Quarkus app (one Gradle build; Containerfile, build context: the repo root)
│   └── src/main/
│       ├── resources/application.properties   all settings: ldap.*, auth.*, sync.*, directory.*
│       ├── resources/routes/*.camel.yaml      common (REST and error config), csv-intake, salesforce-sync,
│       │                                      directory-api
│       ├── resources/templates/, META-INF/openapi.yaml   login page; base OpenAPI document of the token service
│       └── java/com/acmeinsurance/
│           ├── common/      Constants, LDAP config and connection pools, DN helpers, error mapping, web filters
│           ├── sync/        core/ (pure logic, unit-tested), ldap/ (PersonWriter), salesforce/, csv/, web/ (trigger guard)
│           ├── auth/        ldap/Directory, token/ (keys, JWTs, grants, PKCE), web/ (/oauth2, metadata, JWKS)
│           └── directory/   auth/ (sign-in, sessions, guard), model/ (kinds of entry), service/, db/ (SQLite), ldap/
├── frontend/                          InsureDirectory's Angular 22 app (standalone components, signals, CSS grid)
└── samples/csv/{internal,members,other}/   example files for the inbox
```

## Quick start

```sh
cp .env.example .env          # set passwords; SF_ENABLED=false until Salesforce values are in
podman compose up --build -d

# Smoke test, run inside the openldap container (it has ldap-utils)
set -a; . ./.env; set +a
podman cp openldap/smoke-test.sh sf-ldap-sync_openldap_1:/tmp/smoke-test.sh
podman exec -e SVC_SYNC_PASSWORD -e SVC_AUTH_PASSWORD -e SVC_DIRECTORY_PASSWORD \
  sf-ldap-sync_openldap_1 sh /tmp/smoke-test.sh

# InsureDirectory: http://localhost:8080/ (sign in as a user holding the "directory" feature)

# Load the sample people (needs LOAD_SAMPLES=true for the client IDs)
cp -r samples/csv/* inbox/
podman compose logs -f backend

# Salesforce: dry run first (DRY_RUN=true in .env logs writes instead of making them).
# The trigger needs an access token whose features claim holds sync: run-sync
# (grant acmeFeatureDN: cn=run-sync,cn=sync,ou=features,dc=acmeinsurance,dc=com).
TOKEN=$(curl -s -u demo-app:$OAUTH_DEMO_CLIENT_SECRET -d grant_type=password -d username=... \
  --data-urlencode password=... localhost:8080/oauth2/token | jq -r .access_token)
curl -X POST -H "Authorization: Bearer $TOKEN" localhost:8080/sync/salesforce   # run now, returns the report
curl -H "Authorization: Bearer $TOKEN" localhost:8080/sync/salesforce           # last report
```

Without a valid token the trigger answers 401, and without `run-sync` 403 (both with a `WWW-Authenticate: Bearer`
challenge). When a dry-run report looks right, set `DRY_RUN=false` and recreate `backend`.

**API documentation (OpenAPI).** The backend serves two documents:

| Document | URL | Generated from |
|---|---|---|
| Camel REST APIs: `/sync`, `/auth`, `/api` | http://localhost:8080/openapi (`/openapi.yaml` for YAML) | the REST DSL in `routes/*.camel.yaml` |
| Token service: `/oauth2`, `/.well-known` | http://localhost:8080/q/openapi (`?format=json` for JSON) | annotations in `auth/web/` + `META-INF/openapi.yaml` |

- Camel builds the first one at runtime (`camel-quarkus-openapi-java`), so it always matches the routes. The sync
  operations are always listed; with `SF_ENABLED=false` they answer 503 `{"status": "disabled: ..."}`. Title and
  description are in `routes/common.camel.yaml`. `BACKEND_API_HOST` sets the server shown (default `localhost:8080`).
- Swagger UI is only in dev mode (`./gradlew quarkusDev`): this app is the production login service, and OAuth clients
  find its endpoints through the RFC 8414 metadata anyway.
- `OPENAPI_ENABLED=false` stops serving `/q/openapi`. `/openapi` is always on.

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

## InsureDirectory

A web app for maintaining the directory without knowing LDAP, at http://localhost:8080/. Pages:
- **People:** members, agents, internal users and other users.
- **Accounts:** agencies, agreements and books in three columns, plus clients.
- **Reference data:** underwriting companies, and features with their permissions.
- **Query builder** (for developers): builds a filter from conditions, runs it, and saves queries. It shows the search as
  an `ldapsearch` command, an LDAP URL, and a Java class another application can run: with the UnboundID LDAP SDK
  (paged, so it returns more than the server's page limit) or with plain JNDI (no dependency). The Java reads the
  connection from `LDAP_URL`, `LDAP_BIND_DN` and `LDAP_BIND_PASSWORD`.
- **Audit log:** who changed what through the app.

Forms are built from the field list of each kind of entry (`backend/.../directory/model/Kinds.java`). DN-valued attributes
(account and feature grants, an agreement's underwriting company) are picked by name, not typed.

**Sign-in.** Users sign in on the token service's login page (same origin, `/oauth2/authorize`); the app needs no
passwords of its own. The API is the confidential OAuth client `insure-directory` (code + PKCE). It redeems the code
and refreshes in-process, with the same checks as `/oauth2/token`, keeps the tokens on the server, and gives the
browser only an HttpOnly session cookie. Access needs the `directory` feature, or some of its permissions:

| Permission | Allows |
|---|---|
| `view-entries` | Using the app at all: browse and search |
| `edit-entries` | Create, change and delete entries; lock, unlock, reset passwords |
| `run-queries` | The query builder |

Grant it like any other feature, for example to an internal user: `acmeFeatureDN: cn=directory,ou=features,dc=acmeinsurance,dc=com`.

**What can be changed.** The app writes as `cn=svc-directory` and follows the same ownership rule as the syncs:
- Hand-made entries can be changed and deleted.
- On entries owned by Salesforce or the CSV feeds, only what the sync leaves alone can be changed: an agent's feature
  grants, and an agreement's underwriting company. With `SF_MANAGES_UNDERWRITING_COMPANY=true`, an agreement's company
  is read-only too.
- Any person can be locked, unlocked (except when deactivated by their source) and given a temporary password, which
  they must change at the next sign-in.
- Only svc-directory (and the rootdn) can maintain underwriting companies; svc-sync only reads them.

The audit log and saved queries are in SQLite (`DIRECTORY_DB`, a volume at `/data` in the container). The log records
attribute names, never values.

**Setup.** Set `SVC_DIRECTORY_PASSWORD` and `DIRECTORY_CLIENT_SECRET` (32+ characters) in `.env` before the first start;
openldap then creates svc-directory, the feature and the OAuth client. An existing server takes
`openldap/migrations/2026-11-21-add-insure-directory.sh` (see below). The app is reached at `AUTH_ISSUER`
(`DIRECTORY_PUBLIC_URL` overrides it); add `{url}/auth/callback` to the client's `acmeRedirectUri` when it changes.

**Development.**
```sh
cd backend && ./gradlew quarkusDev             # :8083 (LDAP_URL and the SVC_* passwords, DIRECTORY_CLIENT_SECRET from the environment)
cd frontend && npm install && npm start        # Angular on :4200, proxies /api, /auth, /oauth2 and /.well-known to :8083
```
`http://localhost:4200/auth/callback` is a registered redirect URI, so sign-in works in development too.

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
- **Underwriting company:** off by default. To have the sync set it, point
  `sync.accounts.underwriting-company-field` at the agreement field holding the company's name, for example
  `Underwriting_Company__r.Name`, and add that field to `sync.accounts.soql`. The name must match the `cn` of a
  company under `ou=underwritingCompanies` (case does not matter).

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

An OAuth 2.0 authorization server, part of the backend (port 8080). It has no database: users, grants and
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
  "clients":    ["100234"],
  "underwriting_companies": { "AG0042/AGR-0042-01": "Big Insurance UW Co" }
}
```

Grants are listed as held, not expanded. Agreements and books are paths from the agency down, so a resource
server allows book `AG0042/AGR-0042-01/BK-000311` when the path equals a grant or starts with a grant
followed by `/`. A feature grant lists every permission under that feature; a feature with no permissions
(a plain role such as `csr`) appears with an empty list.

`accounts.underwriting_companies` maps each agreement the grants cover to the `cn` of its underwriting company.
That includes a held agreement, the agreement above a held book, and every agreement directly under a held
agency. Agreements without a company are left out. It is reference data, not a grant: the grant lists stay as
held. An agency with many agreements makes the token correspondingly larger.

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

**Underwriting companies.** An agreement names its underwriting company in `acmeUnderwritingCompanyDN`
(single-valued, allowed only on `acmeAgreementAccount`). The possible values are `acmeUnderwritingCompany`
entries under `ou=underwritingCompanies`, maintained as the rootdn (or in phpLDAPadmin):

```ldif
dn: cn=Big Insurance UW Co,ou=underwritingCompanies,dc=acmeinsurance,dc=com
objectClass: acmeUnderwritingCompany
cn: Big Insurance UW Co
displayName: Big Insurance Underwriting Company
```

The constraint overlay refuses a value that is not one of these companies. Refint rewrites agreements when a
company is renamed and clears them when it is deleted. svc-sync and svc-auth can read the companies but not
change them. The Salesforce sync sets the value only when `sync.accounts.underwriting-company-field` is
configured. A company name it can't find is reported as an error, and the agreement keeps its value. Without
the field, values set by hand are kept.

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
     -d username=sample.agent --data-urlencode password=... localhost:8080/oauth2/token
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

A server built before 2026-11-21 has no underwriting companies. Run
`openldap/migrations/2026-11-21-add-underwriting-companies.sh` inside the container (instructions at its top).
It adds the schema, the index, the refint and constraint settings, and `ou=underwritingCompanies`. It is safe to
run again. Then load your companies as the rootdn (or, once InsureDirectory is set up, in its Underwriting companies page).

For InsureDirectory, a server built before 2026-11-21 takes `openldap/migrations/2026-11-21-add-insure-directory.sh`,
run inside the rebuilt openldap container with `SVC_DIRECTORY_PASSWORD` (and `DIRECTORY_CLIENT_SECRET`) passed in
(instructions at its top). It adds `cn=svc-directory`, its limits and the ACLs from `10-config-db.ldif`, the
`directory` feature and the `insure-directory` client. It is safe to run again.

The single backend container (2026-11-21) replaced the sync, auth and directory containers. A server set up before
then takes `openldap/migrations/2026-11-21-consolidate-backend.sh` (run inside the rebuilt openldap container with
`LDAP_ADMIN_PASSWORD`; instructions at its top). It registers `http://localhost:8080/auth/callback` on
`insure-directory` in place of the 8082 one, and adds the `sync` feature with `run-sync`. It is safe to run again.
Remove the old containers before `podman-compose up -d`: `podman rm -f sf-ldap-sync_sync_1 sf-ldap-sync_auth_1
sf-ldap-sync_directory_1`. The `auth-keys` and `directory-data` volumes carry over.

## What has been tested

- **OpenLDAP config:** loaded into a real OpenLDAP 2.6.10.
- **Smoke test:** `smoke-test.sh` passes 39/39 (last run 2026-11-21, slapd 2.6.10 in the openldap image, on a server
  migrated with the 2026-11-21 scripts). The 7 underwriting checks need `LDAP_ADMIN_PASSWORD`, and the 6 svc-directory
  checks `SVC_DIRECTORY_PASSWORD`. It covers:
  - uniqueness
  - the existence constraints on account and feature grants
  - the `acmeUserType` value constraint
  - `refint` on subtree move and delete, for accounts and features
  - lock/unlock and password reset via ppolicy
  - the ACLs (users cannot change their own grants or user type)
  - OAuth clients: a confidential client binds, there is no lockout, svc-auth can tell confidential from public clients, and it cannot read secrets
  - underwriting companies: only companies are accepted, only agreements hold one, svc-auth can read them, and refint follows a company's rename and delete
  - svc-directory: maintains companies and resets passwords (with `pwdReset`), but cannot add OAuth clients or change service accounts; svc-sync cannot create companies
  - the `sync` feature's `run-sync` permission exists
- **Backend (2026-11-21):** `cd backend && ./gradlew build` (Gradle 9.9.1 wrapper, Groovy DSL, needs JDK 25) runs 98
  tests against one in-memory directory loaded with the real schema from `openldap/ldif` (ppolicy simulated): the
  sync's `core/` unit tests and its `PersonWriter`, `SalesforceSyncService` and `CsvIntakeService` integration tests;
  the token service (claims per user type, all grants, client authentication, reset and change password); InsureDirectory
  (entry rules, query validation, sign-in helpers, the HTTP surface without a session); and the sync trigger (401 without
  a token, 403 without `run-sync`). The image build runs them too, since its context is the repository root.
  `cd frontend && npx ng test` runs the filter builder tests. The container was run against the live stack: sign-in
  through `/oauth2/authorize`, CRUD, the ownership and CSRF refusals, the query builder, the audit log, and the sync
  trigger with and without the permission, checked with a script and in headless Chrome.
- **Token service against real slapd (2026-11-19):** the real ppolicy controls (reset forces a change, a too-short
  new password is rejected), the claims for `sample.agent`, and a ppolicy lockout blocking both sign-in and refresh. On
  2026-11-21 a live password-grant token for an agent holding an agency listed the company of that agency's agreement.
- **Paged searches:** with the limits above, paging returns more than 1000 entries.
- **App startup:** with `SF_ENABLED=false` the backend runs the sync on CSV intake alone (the 4 sample people load). With `SF_ENABLED=true` and no credentials it also starts and serves `/sync/salesforce` (a sync run without credentials has not been tried). The LDAP pools are opened on first use, so it also starts while OpenLDAP is down.
- **OpenAPI:** `/openapi` lists the sync, sign-in and InsureDirectory operations whatever `SF_ENABLED` is (2026-11-20: `SF_ENABLED=true` returns the report, `false` returns 503 JSON). `/q/openapi` lists the token service; `OPENAPI_ENABLED=false` turns it off.
- **To confirm against a real org:**
  - `queryMore` paging takes the `nextRecordsUrl` from the message body.
  - The CDC endpoint syntax `salesforce:subscribe:data/ChangeEvents` is right for the Camel version in use.

## Next steps

- Replace the CSV feeds with real sources: Entra ID / Microsoft Graph for staff, and the policy system for members (`camel-jt400` or `camel-sql` against IBM i).
- Replace the placeholder OID arc `1.3.6.1.4.1.99999` with Acme Insurance's IANA Private Enterprise Number.
- Turn on TLS (`ldaps://` is supported by the sync; the server side needs certificates).
