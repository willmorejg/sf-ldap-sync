# CLAUDE.md — acmeinsurance-ldap-sync

Context for continuing this project in Claude Code. README.md explains what the
project does; this file says where things stand and what to do next.

## What this is
A Podman Compose project that keeps Acme Insurance's OpenLDAP directory in sync with
its sources:
- Salesforce Account records (Agency/Agreement/Book record types) become the
  tree under `ou=agencies,ou=accounts`.
- Salesforce Users (all of them are brokers or agents) become entries under
  `ou=agents,ou=people`.
- Internal users, members (insureds) and other users are NOT in Salesforce.
  For now they come from a CSV drop folder (`inbox/{internal,members,other}`).

All Quarkus code is one app, `backend/` (since 2026-11-21; it replaced the `sync/`, `auth/` and
`frontend/api` builds and their three containers). It serves everything on http://localhost:8080:
- `com.acmeinsurance.sync`: the sync above (CSV inbox, Salesforce routes, `/sync/salesforce`).
- `com.acmeinsurance.auth`: an OAuth 2.0 token service. It issues RS256 JWTs carrying a user's features
  (`acmeFeatureDN`) and held account grants (`/oauth2/*`, `/.well-known/*`, the login page).
- `com.acmeinsurance.directory`: InsureDirectory (brief in `USER_MAINTAIN_APP.md`), a directory maintenance app for
  CSRs and developers: Camel REST DSL API (`/api`, `/auth`), SQLite, and the Angular 22 app from `frontend/`, which
  `processResources` copies into `META-INF/resources`.
- `com.acmeinsurance.common`: what they share: `Constants`, `ldap/` (`LdapConfig`, `LdapConnections`, `DnUtil`),
  `web/` (`ApiException`, `ApiErrors`, `WebFilters`).

The schema follows the earlier design: `acmeUser`, `acmeAccountAccess` (holding
`acmeAccountDN` grants), `acmeMemberAccess` (holding `acmePolicyNumber`), and the
`acmeAccount` subclasses. This project adds `acmeSource`, `acmeDeactivated` and the
`acmeSyncManaged` object class (see `openldap/ldif/02-acmeinsurance-sync-schema.ldif`).
`01` also defines `acmeUnderwritingCompanyDN` (allowed only on `acmeAgreementAccount`, single-valued) and the
`acmeUnderwritingCompany` class, whose entries under `ou=underwritingCompanies` are the possible values (2026-11-21).
`03-acmeinsurance-auth-schema.ldif` adds `acmeFeature`/`acmePermission` (under `ou=features`), the
`acmeFeatureAccess` auxiliary class with `acmeFeatureDN`, and `acmeOAuthClient` (under
`ou=oauth-clients,ou=services`).

## Status
| Part | State |
|---|---|
| `openldap/` (config LDIFs, entrypoint, Containerfile) | Loaded and exercised against a real slapd 2.6.10; `openldap/smoke-test.sh` passes 39/39 (2026-11-21, on the migrated compose volumes; 32/32 on a fresh load before the svc-directory checks) |
| `backend/` | Built 2026-11-21 from the three apps. Java 25 / Quarkus 3.40.1 LTS (Camel 4.22). 98 tests pass against one in-memory directory (`common/testsupport/TestDirectory`, ppolicy simulated by an interceptor): sync core + integration (49), token service (17), InsureDirectory (28), sync trigger (4). The image build runs them (context: repo root). Run against the live stack: InsureDirectory sign-in, CRUD, ownership and CSRF refusals, query builder, audit; sync trigger 401/403/503; checked with a script and headless Chrome. Starts without Salesforce credentials and without OpenLDAP (pools open on first use) |
| sync | `core/` compiles with `-Xlint:all`. With `SF_ENABLED=false` only CSV intake runs. Salesforce not yet exercised |
| token service | Run end to end against real slapd 2.6.10 on 2026-11-19: reset forces a password change on the login page, claims correct, lockout blocks sign-in and refresh. OpenAPI at `/q/openapi` from annotations |
| phpLDAPadmin (in the openldap image) | 1.2.6.7 from Ubuntu, on nginx + php8.3-fpm, port 8090 on the host. Checked 2026-11-20: admin login, entry/feature/client views, schema list, tree |
| Container images and compose | Two services, openldap and backend, under podman-compose 1.5. Live server migrated with `2026-11-21-consolidate-backend.sh`; the `auth-keys` and `directory-data` volumes carried over |
| `frontend/` (InsureDirectory UI) | Angular 22: builds, 6 vitest tests. Checked at desktop and 390px. The query builder generates Java (`pages/query-code.ts`, UnboundID or JNDI); both outputs were compiled with `-Xlint:all` and run against the live directory on 2026-11-21 |
| Documentation | `docs/`: index, schema requirements, Mermaid sequence diagrams (2026-11-20); all diagrams render with Mermaid 11 |

## Do first
1. ~~Compile and run core tests~~ Done (2026-11-19): builds with JDK 25, 11 tests pass.
   Build with `cd backend && ./gradlew build` (Groovy DSL, Gradle 9.9.1 wrapper, Java 25
   toolchain). Set `JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64` first: the default
   SDKMAN java is 21, and the system Gradle 8.8 can't run on 25. Every `sync.*` property must be declared in `SyncConfig`,
   including ones only the routes read, or startup fails with SRCFG00050.
2. ~~Integration tests~~ Done (2026-11-19): `PersonWriterTest` and
   `SalesforceSyncServiceTest` (24 tests) run against an in-memory directory
   built by `testsupport/TestDirectory` from `openldap/ldif`, with acmeAccountDN
   refint. Its test schema stores pwdAccountLockedTime as a string, because
   UnboundID rejects year 0000 in `000001010000Z`. Original brief: integration tests for `PersonWriter` and `SalesforceSyncService` using
   UnboundID's `InMemoryDirectoryServer`, loading the schema LDIFs. Use a fake
   `SalesforceReader` with canned records. These tests matter most: they cover
   create, rename/move, grant pruning scoped to `ou=agencies`, locking, and the
   safety guard.
3. ~~Compose up, smoke test, CSV samples~~ Done (2026-11-19). Fixes it needed, all
   in compose.yaml / the sync app:
   - the healthcheck is defined in compose.yaml, because podman builds OCI images,
     which drop the Containerfile `HEALTHCHECK` (sync waits for `service_healthy`)
   - `x-podman: in_pod: false` plus `userns_mode: keep-id:uid=1001,gid=0` on sync,
     so the container user can write the bind-mounted `inbox/` under rootless Podman
   - the Salesforce component is built by `SalesforceComponentProducer` from
     `sync.salesforce.*` (not `camel.component.salesforce.*`, which starts the
     component at boot and fails without a client ID); `SF_ENABLED=false` drops the
     Salesforce routes via route preconditions
   The host has no ldap-utils; run the smoke test inside the openldap container
   (see README).
4. Against a Salesforce sandbox with `DRY_RUN=true`, run
   `curl -X POST -H "Authorization: Bearer $TOKEN" localhost:8080/sync/salesforce` (a token with sync > run-sync)
   and check:
   - `queryMore` paging: the code passes `nextRecordsUrl` in the body
   - the CDC endpoint syntax `salesforce:subscribe:data/ChangeEvents` for the
     Camel version in use
   - CLIENT_CREDENTIALS login through `SalesforceComponentProducer` (setter names
     are now compile-checked; the login itself is untested)

5. ~~Recreate the compose volumes for the auth schema~~ Done (2026-11-20): the volumes were
   recreated for the Acme rename, so they include the auth schema and svc-sync. An existing server
   still takes the add-on by hand (README, "Applying the sync add-on").

## Assumptions about the Salesforce org (all in application.properties)
- Record types are named Agency/Agreement/Book and linked by ParentId.
- `cn` comes from AccountNumber.
- An agent's agency comes from Contact.AccountId (partner users).
- Extra grants come from AccountContactRelation (commented out by default).
- `uid` comes from Username.

## Design rules (don't break these)
- A source only modifies entries whose `acmeSource` it owns. Hand-made entries
  are untouched unless `sync.safety.adopt-unowned=true`.
- The Salesforce sync only manages an agent's `acmeAccountDN` values under
  `ou=agencies`. Client grants (`ou=clients`) are never touched.
- Agents who disappear are locked (`pwdAccountLockedTime=000001010000Z` plus
  `acmeDeactivated`), never deleted. Accounts that disappear are deleted
  children-first, and refint cleans up the grants.
- SafetyGuard: an empty source never removes anything, and one run removes at
  most `max-remove-percent` of a source's entries.
- Passwords are never written by the sync. Anything that resets a password through
  svc-sync must set `pwdReset: TRUE` in the same operation: ppolicy only sets it by
  itself for `manage` access, and svc-sync keeps plain `write` so its resets still
  pass the password policy.
- `acmeUserType` is limited to internal/agent/member/other by the constraint overlay.
- Keep `LOAD_SAMPLE_AGENCIES=false` on any server the Salesforce sync feeds.
- Paged searches must stay on one connection (OpenLDAP ties the cookie to it).
  The svc-sync/svc-auth `olcLimits` must stay, or searches stop at 1000 entries.

- The sync trigger (`/sync/salesforce`, both verbs) needs `Authorization: Bearer` with an access token of this app
  whose `features` claim holds `sync: run-sync` (`38-sync-feature.ldif`). `SyncGuard` verifies it in-process
  (`TokenService.verifyAccess`) before anything else: 401 `invalid_token` or 403 `insufficient_scope`, with a
  `WWW-Authenticate: Bearer` challenge. The routes then remove all request headers.
- Each part of the backend binds as its own account (`ldap.auth`, `ldap.sync`, `ldap.directory`: svc-auth, svc-sync,
  svc-directory), so the ACLs still separate them. Don't share a pool between parts.
- Token service: it only reads, as svc-auth, and checks passwords by binding as the user, so
  ppolicy stays authoritative. It never writes, except a user's own password change. Grants in
  tokens are as held (not expanded); agreements and books are agency-rooted paths.
  `accounts.underwriting_companies` (agreement path to company cn) is reference data, not a grant: it covers held
  agreements, the agreement above a held book and the agreements directly under a held agency (2026-11-21). A refresh
  re-reads LDAP and is refused for a locked, deactivated or reset user, or after a password
  change. svc-auth has `search` (not `read`) on client `userPassword`, only under
  `ou=oauth-clients`, to tell confidential clients from public ones. Keep that ACL ahead of the
  general `userPassword` rule.
- The sync never touches `acmeFeatureDN`. The Salesforce sync never sends feature grants, and adds only the
  `acmeFeatureAccess` class to agents. The CSV feeds own the feature grants of entries they own.
- The provisioning account is `cn=svc-sync` (env `SVC_SYNC_PASSWORD`), renamed from svc-idm on
  2026-11-20 to match `docs/LDAP_Schema_Definition.md`. Older servers are migrated with
  `openldap/migrations/2026-11-20-rename-svc-idm-to-svc-sync.sh`. The script names `dc=acmeinsurance,dc=com`, so it fits only
  a server already on the Acme base; the compose volumes were created with svc-sync and need no migration.
- `docs/LDAP_Schema_Definition.md` is the requirements document for user types. In short:
  - Token claims by user type: `features` and `accounts` for agent, internal and other; `policies` for
    member and other.
  - Members hold policy numbers only, and internal users never hold them.
  - All CSV feeds write `acmeSource=csv` (legacy `csv-*` values still count as owned).
  - `acmeSource=manual` counts as hand-made.
  - Public OAuth clients never get refresh tokens.
- phpLDAPadmin lives in the openldap container: nginx and php-fpm daemonize from entrypoint.sh
  before `exec slapd`, so slapd stays PID 1 and a web failure only logs a warning. Use nginx, not
  Apache. Keep `pla_password_hash` empty (clear), so slapd hashes with Argon2 under ppolicy.
  Two PHP 8.3 workarounds: `common.php` is patched to leave out `E_DEPRECATED`, and the host is
  set as an `ldap://` URI with port 0. The image build runs `nginx -t`.
- Underwriting companies are maintained by hand (rootdn); svc-sync and svc-auth only read `ou=underwritingCompanies`.
  The Salesforce sync touches an agreement's `acmeUnderwritingCompanyDN` only when
  `sync.accounts.underwriting-company-field` is set; an unknown company name is an error and the value is kept.
  Servers built before 2026-11-21 take `openldap/migrations/2026-11-21-add-underwriting-companies.sh`
  (idempotent; the compose volumes have had it applied).
- InsureDirectory (`backend/.../directory`, UI in `frontend/`):
  - Backend for frontend: the API is the confidential OAuth client `insure-directory` (code + PKCE, secret
    `DIRECTORY_CLIENT_SECRET`). The browser is sent to `/oauth2/authorize` on the same origin; the code is redeemed
    and tokens refreshed in-process through `TokenGrants`, which `/oauth2/token` also uses, so the checks are the same
    (the client still authenticates with its secret). Tokens stay in in-memory server sessions; the browser holds only the HttpOnly
    `idir_session` cookie (SameSite=Lax). Requests other than GET need `X-Requested-With: InsureDirectory`. The guard
    refreshes the access token 60 s before expiry, so the token service's refresh checks (locked, deactivated, reset,
    password changed) end a session.
  - Access is the `directory` feature (`36-insure-directory.ldif`): `view-entries` to use the app, `edit-entries` to
    change, `run-queries` for the query builder. Read from the token's `features.directory` claim.
  - It writes as `cn=svc-directory` (env `SVC_DIRECTORY_PASSWORD`) and follows the ownership rule: on entries with
    `acmeSource` salesforce or csv only the attributes a kind lists in `editableWhenSalesforce` (agent `acmeFeatureDN`,
    agreement `acmeUnderwritingCompanyDN` unless `SF_MANAGES_UNDERWRITING_COMPANY=true`) can change, and they cannot be
    deleted. New entries get `acmeSource=manual`. Lock/unlock/reset work on every person; unlock is refused when
    `acmeDeactivated` is set. A reset writes `userPassword` and `pwdReset: TRUE` together.
  - ACL order in `10-config-db.ldif`: {0} oauth-client secrets, {1} `ou=people` userPassword (svc-sync and
    svc-directory write), {2} other userPassword, {3} entitlement attributes, {4} people, {5} accounts, {6} features,
    {7} `ou=underwritingCompanies` (svc-directory write, others read), {8} the rest. svc-directory must never get write
    on `ou=services`. Servers built before 2026-11-21 take `openldap/migrations/2026-11-21-add-insure-directory.sh`
    (needs the rebuilt image; replaces all olcAccess/olcLimits values with those of `10-config-db.ldif`).
    Servers set up before the single backend take `openldap/migrations/2026-11-21-consolidate-backend.sh` (redirect
    URI 8082 to 8080, adds the `sync` feature; idempotent; applied to the compose volumes).
  - Kinds of entry and their fields live in `model/Kinds.java` and drive both the API and the Angular forms; add an
    attribute there, not in the UI. Entry IDs in URLs are base64url DNs. The SQLite file holds only the audit log
    (attribute names, never values) and saved queries.
  - Every REST verb in `routes/directory-api.camel.yaml` forwards to its own `direct:` route, which removes all request
    headers before answering (so the Cookie header is never echoed). The UI is copied into `META-INF/resources` by
    `processResources` from `../frontend/dist`; `common/web/WebFilters` serves `index.html` for app paths (anything
    outside `/api`, `/auth`, `/oauth2`, `/.well-known`, `/sync`, `/q`, `/openapi`) and sets the security headers: a
    strict CSP for the token service's pages, the app's CSP elsewhere. Keep
    `inlineCritical: false` in `angular.json`: the CSP blocks the inline handler it adds.
- Recreating only openldap under podman-compose fails while backend depends on it: remove
  backend, then openldap (`podman rm -f`), then `podman-compose up -d`. Volumes are kept.

## Documentation
All documentation except README.md (and this file, which Claude Code expects at the root) lives in
`docs/`. Sequence diagrams are Markdown files with embedded Mermaid in `docs/sequence/`, listed in
`docs/index.md`. When a flow changes (routes, `SalesforceSyncService`, `CsvIntakeService`,
`PersonWriter`, `AuthorizeResource`, `TokenResource`, `entrypoint.sh`), update its diagram too.

## Code quality
SonarCloud analyses `backend/` from `.github/workflows/scan.yml` (`./gradlew build sonar`). Coverage comes from
JaCoCo 0.8.14 (the first release for Java 25 class files): `test` is finalized by `jacocoTestReport`, and the XML path is
set as `sonar.coverage.jacoco.xmlReportPaths`. Locally 65.6% of lines (2026-11-21; the token service and InsureDirectory
are analysed now too, and their HTTP paths are less covered than the sync's 83.8%).

## OpenAPI
The backend serves two documents. Keep both; Quarkus cannot merge YAML DSL routes into its own.
- `/openapi`: the Camel REST DSL (`/sync`, `/auth`, `/api`), built at runtime by `camel-quarkus-openapi-java`
  (`camel.rest.api-context-path`). The single `restConfiguration` (info) and the `api-errors` `routeConfiguration`
  live in `routes/common.camel.yaml`; never declare another `restConfiguration` (the `camel.rest.api-property[...]`
  property syntax fails to bind, so info stays in YAML). `camel.rest.api-host` (`BACKEND_API_HOST`) stops the spec
  advertising 0.0.0.0. No off-switch.
  - The sync's REST block is always on, so the spec always lists both operations; never set `disabled:` on it
    (Camel then drops the operations). Each verb forwards to a `direct:` endpoint served by one of two routes,
    picked by precondition: the real one (`{{sync.salesforce.enabled}}`) or a `-disabled` one
    (`{{!sync.salesforce.enabled}}`) that answers 503 JSON. Both run `syncGuard` first. That needs
    `camel-quarkus-direct` and `camel.rest.inline-routes=false`: with inlining (the Camel 4 default) the verbs
    inherit the wrong precondition and 404 when Salesforce is enabled.
- `/q/openapi`: the token service, from MicroProfile OpenAPI annotations on `auth/web/*Resource`, merged with
  `src/main/resources/META-INF/openapi.yaml` (info, the `clientBasic` scheme, the form/response schemas referenced by
  `@Schema(ref = ...)`). Switch: `OPENAPI_ENABLED` (`quarkus.smallrye-openapi.enabled`; `.enable` is deprecated).
  Swagger UI stays dev/test only: this app is the production login service.
- podman-compose `up --build` rebuilds images but keeps running containers: `podman rm -f` the
  service's container, then `podman-compose up -d`.

## Conventions
Java 25, Gradle (Groovy DSL, wrapper), Camel Quarkus with YAML DSL routes (logic lives in CDI beans, and the
routes only trigger it), rootless Podman, OpenLDAP config via cn=config LDIF. One Gradle build, `backend/`; its tests
read `../openldap/ldif`. Tests share `common/testsupport`: `TestDirectory` (pass `SYNC_SAMPLES` or `ALL_SAMPLES`),
`TestLdapConfig` for plain unit tests, and `LdapTestResource`, which every `@QuarkusTest` gets (declared once, not
restricted), so the app starts once.

`frontend/`: Angular standalone components with signals and the new control flow, no UI library, CSS grid for layout
(`src/styles.css`), Node 24 from nvm (`npm run build`, `npx ng test`). `ng serve` proxies the backend paths to :8083
(`./gradlew quarkusDev`).

`final` everywhere the compiler allows it: local, catch and for-each variables, method and constructor
parameters (not lambda or pattern variables, not record components), private fields set once, and classes
nothing extends. Exceptions: CDI normal-scoped beans (`@ApplicationScoped`) stay non-final because Quarkus
proxies them, and `@QuarkusTest` classes stay non-final. Beans use constructor injection (a single constructor,
no `@Inject`) so their dependencies are `private final`; don't add `@Inject` fields.
`@Singleton`/`@Provider`/JAX-RS resource classes may be final.

No identifier as a string literal. Names used in more than one class live in `common/Constants.java`, an enum with
only `INSTANCE`, used through static imports: LDAP attributes, object classes and RDNs, acmeSource/acmeUserType
values, features and permissions, OAuth parameters, grant types and error codes, JWT claims, HTTP headers and paths,
InsureDirectory's request parameters (`PARAM_*`), JSON fields (`JSON_*`) and error codes, kinds of entry, CSV
folders, columns and reference prefixes. Names used by one class are `private static final` there (SQL and column
names, audit actions, cookie attributes, report fields; `SyncReport` holds the counter names). Where two constants
share a value (`PARAM_PASSWORD`/`GRANT_TYPE_PASSWORD`, `PARAM_CODE`/`RESPONSE_TYPE_CODE`), pick the one that names the
role. Messages, log text, OpenAPI annotation text, config defaults in `@WithDefault`, CDI bean names (the YAML routes
name them), login template data keys and RFC 8414 metadata keys stay literals. Keep new lines within 120 characters
(14 older lines are longer).
