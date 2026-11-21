# OpenLDAP container startup

What `openldap/entrypoint.sh` does when the container starts, and how the other
services wait for it. On first start, the configuration and the directory tree are
built offline with `slapadd`. Later starts reuse the volumes as they are.

```mermaid
sequenceDiagram
    autonumber
    participant PC as podman-compose
    participant E as entrypoint.sh
    participant SA as slapadd
    participant W as nginx + php-fpm
    participant D as slapd
    participant B as backend

    PC->>E: start openldap (env from .env)
    alt first start (no cn=config.ldif)
        E->>E: require LDAP_ADMIN_PASSWORD, SVC_SYNC_PASSWORD, SVC_AUTH_PASSWORD, SVC_DIRECTORY_PASSWORD
        E->>E: Argon2-hash the passwords
        E->>SA: -n0: 00-config-global + core/cosine/inetorgperson + 01/02/03 acmeinsurance schemas + 10-config-db
        E->>SA: -n1: 20-dit (base, OUs, service accounts, ppolicy subentries)
        E->>SA: 36 directory feature, 38 sync feature (always)
        opt DIRECTORY_CLIENT_SECRET set
            E->>SA: 37 insure-directory OAuth client (hashed secret)
        end
        opt LOAD_SAMPLES=true
            E->>SA: 30 sample clients, 33 public OAuth client, 32 features
            opt OAUTH_DEMO_CLIENT_SECRET set
                E->>SA: 34 confidential OAuth client (hashed secret)
            end
        end
        opt LOAD_SAMPLE_AGENCIES=true (never on a Salesforce-fed server)
            E->>SA: 32 features, 31 sample agencies and agent
        end
    else later start
        E->>E: keep existing config and data volumes
    end

    opt PHPLDAPADMIN_ENABLED=true
        E->>E: write a random blowfish secret to /run/phpldapadmin
        E->>W: php-fpm8.3 --daemonize, nginx (port 8080 → host 8090)
        alt failed
            W-->>E: error
            E->>E: log a warning, continue (slapd still starts)
        end
    end

    E->>D: exec slapd (PID 1, port 1389 and ldapi)
    loop healthcheck (defined in compose.yaml)
        PC->>D: anonymous base search for namingContexts
        D-->>PC: result
    end
    PC->>B: start when openldap is healthy
    B->>B: load or generate RS256 signing key in /keys
    Note over B,D: each part opens its pool on first use and binds as its own account
    B->>D: sync: bind as cn=svc-sync (CSV intake, startup sync)
    B->>D: token service: bind as cn=svc-auth (lookups), user binds on separate connections
    B->>D: InsureDirectory: bind as cn=svc-directory
```

## phpLDAPadmin sign-in

phpLDAPadmin runs in the same container and talks to slapd over the loopback
interface. It uses cookie authentication: the bind password is kept in a browser
cookie, encrypted with a random key that is generated at every container start.
Restarting the container therefore signs everyone out.

```mermaid
sequenceDiagram
    autonumber
    actor Ad as Administrator
    participant N as nginx :8080
    participant P as php-fpm (phpLDAPadmin)
    participant D as slapd 127.0.0.1:1389

    Ad->>N: GET http://localhost:8090/
    N->>P: FastCGI index.php
    P-->>Ad: login form (Acme Insurance)
    Ad->>N: POST login (cn=admin DN, password)
    N->>P: FastCGI cmd.php
    P->>D: bind as cn=admin
    D-->>P: success
    P-->>Ad: tree view of dc=acmeinsurance,dc=com
    Note over P,D: password changes are sent in clear text (pla_password_hash empty), so slapd hashes them with Argon2 under ppolicy
```
