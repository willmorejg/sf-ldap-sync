# Salesforce sync

How Salesforce Accounts and Users reach the directory. All of the logic is in
`SalesforceSyncService` (`sync/src/main/java/com/acmeinsurance/ldapsync/salesforce/`).
The Camel routes in `sync/src/main/resources/routes/salesforce-sync.camel.yaml`
only decide when it runs. With `SF_ENABLED=false`, none of these routes is created, and
`/sync/salesforce` answers 503 with a `disabled` status.

The HTTP trigger is a Camel REST DSL endpoint, which forwards to a `direct:` route.
Camel generates its OpenAPI document from that definition and serves it at
`http://localhost:8080/openapi`, with Swagger UI at `http://localhost:8080/q/swagger-ui/`.

## Triggers

Every trigger calls the same `fullSync()`. It is a complete, idempotent comparison,
so running it more often is always safe. A lock means only one run happens at a
time, and a trigger that arrives during a run is skipped.

```mermaid
sequenceDiagram
    autonumber
    participant Q as Quartz (02:15 nightly)
    participant T as Startup timer (+15 s)
    participant Op as Operator
    participant SF as Salesforce CDC
    participant D as Debounce timer (2 min)
    participant S as SalesforceSyncService

    Q->>S: fullSync()
    T->>S: fullSync() (once, if run-on-startup)
    Op->>S: POST /sync/salesforce (REST DSL → direct:salesforce-sync-now) → fullSync()
    S-->>Op: SyncReport (JSON)
    Op->>S: GET /sync/salesforce (→ direct:salesforce-sync-last) → lastReport()
    S-->>Op: last SyncReport

    opt sync.cdc.enabled=true
        SF->>S: change event → markDirty()
        Note over S: dirty = true (a burst of events costs one run)
        loop every sync.cdc.debounce-ms
            D->>S: runIfDirty()
            alt dirty
                S->>S: fullSync()
            else clean
                S-->>D: null (nothing logged)
            end
        end
    end
```

## Full sync: agencies, agreements and books

Accounts are written level by level (agency, then agreement, then book), and the
directory is re-read before each level, so a child is always placed under its
parent's current DN. If a record can't be written, its children are skipped.

```mermaid
sequenceDiagram
    autonumber
    participant S as SalesforceSyncService
    participant SF as Salesforce (SOQL)
    participant P as AccountMapper / AccountTreePlanner
    participant L as OpenLDAP (as svc-sync)
    participant G as SafetyGuard

    S->>SF: query(sync.accounts.soql)
    SF-->>S: Account rows (queryMore paging)
    S->>P: map rows by record type, plan tree under ou=agencies
    P-->>S: ordered plan + rejected records (reported as errors)

    loop each level: agency, agreement, book
        S->>L: search acmeSalesforceId=* under ou=agencies
        L-->>S: existing entries indexed by Salesforce ID
        loop each planned record at this level
            alt parent failed earlier
                S->>S: skip, report error
            else not in directory, nothing at target DN
                S->>L: add entry (acmeSyncManaged, acmeSource=salesforce)
            else not in directory, hand-made entry at target DN
                alt sync.safety.adopt-unowned=true
                    S->>L: modify: add acmeSalesforceId, acmeSource
                else
                    S->>S: report error, leave it alone
                end
            else exists, owned by another source
                S->>S: report error, leave it alone
            else exists, owned by salesforce
                opt DN changed (cn or parent changed)
                    S->>L: modifyDN (rename or move)
                end
                opt displayName changed
                    S->>L: modify displayName
                end
            end
        end
    end

    S->>L: search owned accounts (acmeSource=salesforce)
    L-->>S: owned entries
    S->>G: checkRemoval(sourceRows, owned, gone, max-remove-percent)
    alt empty source or too many removals
        G-->>S: refusal (reported, nothing removed)
    else allowed
        loop gone entries, deepest first
            S->>L: delete entry
            Note over L: refint removes the DN from every acmeAccountDN
        end
    end
```

## Full sync: brokers and agents

People are written through `PersonWriter`, which applies the ownership rule. Agents
who no longer come back from Salesforce are locked, never deleted.

```mermaid
sequenceDiagram
    autonumber
    participant S as SalesforceSyncService
    participant SF as Salesforce (SOQL)
    participant M as AgentMapper
    participant W as PersonWriter
    participant L as OpenLDAP (as svc-sync)
    participant G as SafetyGuard

    S->>L: search acmeSalesforceId=* under ou=agencies
    L-->>S: Salesforce ID → account DN index
    S->>SF: query(sync.agents.soql)
    SF-->>S: User rows
    opt entitlement SOQL configured
        S->>SF: query(AccountContactRelation)
        SF-->>S: extra grants per contact
    end
    S->>M: map users + grants, resolve account DNs
    M-->>S: PersonSpec list + warnings

    loop each PersonSpec
        S->>W: upsert(spec, agent profile)
        W->>L: find by acmeSalesforceId in ou=agents, else by uid
        L-->>W: entry or none
        alt none and active
            W->>L: add entry (acmeSource=salesforce, ppolicy subentry)
        else none and inactive
            W->>W: skip (skippedInactive)
        else uid used elsewhere, or not owned and not adoptable
            W->>W: report error
        else owned
            opt uid changed
                W->>L: modifyDN
            end
            W->>L: modify attributes, add/prune acmeAccountDN under ou=agencies only
            Note over W,L: grants outside ou=agencies (ou=clients) and acmeFeatureDN are never touched
            opt reactivated
                W->>L: delete acmeDeactivated and the permanent lock
            end
        end
    end

    S->>L: search owned agents in ou=agents
    L-->>S: owned entries
    S->>G: checkRemoval(sourceRows, owned, gone, max-remove-percent)
    alt refused
        G-->>S: refusal (reported, nobody locked)
    else allowed
        loop agents missing from Salesforce
            S->>W: deactivate(entry)
            W->>L: pwdAccountLockedTime=000001010000Z, acmeDeactivated=now
        end
    end
    S-->>S: SyncReport stored as lastReport()
```
