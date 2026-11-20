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

A third container, `auth/`, is an OAuth 2.0 token service (Quarkus). It issues
RS256 JWTs carrying an agent's features (`acmeFeatureDN`) and held account grants.

The schema follows the earlier design: `acmeUser`, `acmeAccountAccess` (holding
`acmeAccountDN` grants), `acmeMemberAccess` (holding `acmePolicyNumber`), and the
`acmeAccount` subclasses. This project adds `acmeSource`, `acmeDeactivated` and the
`acmeSyncManaged` object class (see `openldap/ldif/02-acmeinsurance-sync-schema.ldif`).
`03-acmeinsurance-auth-schema.ldif` adds `acmeFeature`/`acmePermission` (under `ou=features`), the
`acmeFeatureAccess` auxiliary class with `acmeFeatureDN`, and `acmeOAuthClient` (under
`ou=oauth-clients,ou=services`).

## Status
| Part | State |
|---|---|
| `openldap/` (config LDIFs, entrypoint, Containerfile) | Loaded and exercised against a real slapd 2.6.10; `openldap/smoke-test.sh` passes 25/25 (rerun 2026-11-20 on the Acme fresh load) |
| `sync/src/main/java/.../core/` (pure logic) | Compiles cleanly with `-Xlint:all`; 11 unit tests pass |
| `sync/.../ldap`, `salesforce`, `csv`, `SyncConfig`, Gradle build, routes | Compiles on Java 25 / Quarkus 3.40.1 LTS (Camel 4.22). 45 tests pass, including the in-memory LDAP integration tests (CSV intake per user type since 2026-11-20). Starts without Salesforce credentials; with `SF_ENABLED=false` only CSV intake runs. OpenAPI from the Camel REST DSL at `/openapi`, Swagger UI at `/q/swagger-ui` (2026-11-20) |
| `auth/` (token service) | 16 tests pass (in-memory LDAP, ppolicy simulated by an interceptor; claims per user type). Run end to end against real slapd 2.6.10 on 2026-11-19: reset forces a password change on the login page, claims correct, lockout blocks sign-in and refresh. Image builds. OpenAPI at `/q/openapi` from annotations (2026-11-20) |
| phpLDAPadmin (in the openldap image) | 1.2.6.7 from Ubuntu, on nginx + php8.3-fpm, port 8090 on the host. Checked 2026-11-20: admin login, entry/feature/client views, schema list, tree |
| Container images and compose | Built and running under podman-compose 1.5. Fresh load with the Acme names on 2026-11-20: openldap healthy, smoke test 25/25, CSV samples loaded (4 people), auth and sync up. Salesforce not yet exercised |
| Documentation | `docs/`: index, schema requirements, Mermaid sequence diagrams (2026-11-20); all diagrams render with Mermaid 11 |

## Do first
1. ~~Compile and run core tests~~ Done (2026-11-19): builds with JDK 25, 11 tests pass.
   Build with `cd sync && ./gradlew build` (Groovy DSL, Gradle 9.9.1 wrapper, Java 25
   toolchain). Set `JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64` first: the default
   SDKMAN java is 21, and the system Gradle 8.8 can't run on 25. Every `sync.*` property must be declared in `SyncConfig`,
   including ones only the routes read, or startup fails with SRCFG00050.
2. ~~Integration tests~~ Done (2026-11-19): `PersonWriterTest` and
   `SalesforceSyncServiceTest` (24 tests) run against an in-memory directory
   built by `testsupport/TestDirectory` from `openldap/ldif`, with acmeAccountDN
   refint. Its test schema stores pwdAccountLockedTime as a string, because
   UnboundID rejects year 0000 in `000001010000Z`. The image build skips tests,
   since the build context is `sync/` only. Original brief: integration tests for `PersonWriter` and `SalesforceSyncService` using
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
   `curl -X POST localhost:8080/sync/salesforce` and check:
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

- Token service: it only reads, as svc-auth, and checks passwords by binding as the user, so
  ppolicy stays authoritative. It never writes, except a user's own password change. Grants in
  tokens are as held (not expanded); agreements and books are agency-rooted paths. A refresh
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
- Recreating only openldap under podman-compose fails while sync/auth depend on it: remove
  sync, auth, then openldap (`podman rm -f`), then `podman-compose up -d`. Volumes are kept.

## Documentation
All documentation except README.md (and this file, which Claude Code expects at the root) lives in
`docs/`. Sequence diagrams are Markdown files with embedded Mermaid in `docs/sequence/`, listed in
`docs/index.md`. When a flow changes (routes, `SalesforceSyncService`, `CsvIntakeService`,
`PersonWriter`, `AuthorizeResource`, `TokenResource`, `entrypoint.sh`), update its diagram too.

## OpenAPI
- sync: the HTTP endpoints are Camel REST DSL in `routes/salesforce-sync.camel.yaml`. The REST block is
  always on, so the spec always lists both operations; never set `disabled:` on it (Camel then drops the
  operations from the spec). Each verb forwards to a `direct:` endpoint served by one of two routes,
  picked by precondition: the real one (`{{sync.salesforce.enabled}}`) or a `-disabled` one
  (`{{!sync.salesforce.enabled}}`) that answers 503 JSON. That needs `camel-quarkus-direct` and
  `camel.rest.inline-routes=false`: with inlining (the Camel 4 default) the verbs inherit the wrong
  precondition and 404 when Salesforce is enabled. `camel-quarkus-openapi-java` builds the spec at runtime and
  Camel serves it at `/openapi` (`camel.rest.api-context-path`); the extension registers it with Swagger UI
  (`/q/swagger-ui`, `always-include=true`). `quarkus.smallrye-openapi.enable=false`: Quarkus's own
  `/q/openapi` would be empty, because `quarkus.camel.openapi.expose.enabled` only reads Java
  `RouteBuilder` classes, not YAML DSL. Info lives in the YAML `restConfiguration` (the
  `camel.rest.api-property[...]` property syntax fails to bind). `camel.rest.api-host` stops the spec
  advertising 0.0.0.0. No runtime off-switch; the port is loopback-only.
- auth: `quarkus-smallrye-openapi`, spec at `/q/openapi`, switch `OPENAPI_ENABLED`. Spec generated from MicroProfile OpenAPI annotations on `web/*Resource`, merged with the base
  `auth/src/main/resources/META-INF/openapi.yaml`, which holds info, the `clientBasic` scheme and the
  form/response schemas referenced by `@Schema(ref = ...)`. Swagger UI stays dev/test only.
- podman-compose `up --build` rebuilds images but keeps running containers: `podman rm -f` the
  service's container, then `podman-compose up -d`.

## Conventions
Java 25, Gradle (Groovy DSL, wrapper), Camel Quarkus with YAML DSL routes (logic lives in CDI beans, and the
routes only trigger it), rootless Podman, OpenLDAP config via cn=config LDIF. `auth/` is a separate Gradle build with its own
wrapper (same JDK 25 setup as `sync/`); its tests also read `../openldap/ldif`.

`final` everywhere the compiler allows it: local, catch and for-each variables, method and constructor
parameters (not lambda or pattern variables, not record components), private fields set once, and classes
nothing extends. Exceptions: CDI normal-scoped beans (`@ApplicationScoped`) stay non-final because Quarkus
proxies them, and `@QuarkusTest` classes stay non-final. Beans use constructor injection (a single constructor,
no `@Inject`, as in `auth/`) so their dependencies are `private final`; don't add `@Inject` fields.
`@Singleton`/`@Provider`/JAX-RS resource classes may be final. Keep new lines within 120 characters (22 older lines, mostly in tests, are longer).