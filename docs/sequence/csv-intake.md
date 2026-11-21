# CSV intake

Internal users, members (insureds) and other users are not in Salesforce. Until
they have their own sources, they come from CSV files dropped into
`inbox/internal`, `inbox/members` or `inbox/other`. The folder decides the user
type. The route is `backend/src/main/resources/routes/csv-intake.camel.yaml`, and the
logic is in `CsvIntakeService`.

Every row is an upsert, so dropping the same file again is safe. A row with
`active=false` locks the entry, and rows never delete anything. All three feeds
write `acmeSource=csv`.

```mermaid
sequenceDiagram
    autonumber
    actor Op as Operator
    participant FS as inbox/ (bind mount)
    participant R as Camel file route
    participant C as CsvIntakeService
    participant W as PersonWriter
    participant L as OpenLDAP (as svc-sync)

    Op->>FS: drop members/2026-11-20.csv
    R->>FS: poll, wait until the file stops changing (readLock=changed)
    R->>C: load(stream, "members/2026-11-20.csv")
    C->>C: pick profile from folder (internal / member / other)

    loop each row
        C->>C: map columns (CsvRowMapper)
        C->>C: checkAllowed: members hold policy numbers only, internal users none
        opt features column
            C->>C: resolve quoting, quoting/read-quotes or a DN under ou=features
        end
        opt accounts column has sf:<id>
            C->>L: search acmeSalesforceId=* under ou=accounts (once per file)
            L-->>C: Salesforce ID → DN index
        end
        C->>W: upsert(spec, profile)
        W->>L: find by uid under ou=people
        L-->>W: entry or none
        alt none and active
            W->>L: add entry (acmeSource=csv)
        else owned by csv (or legacy csv-*)
            W->>L: modify attributes and grants, lock or unlock
        else uid in another OU, or owned by another source
            W-->>C: error for this row
        end
        alt LDAP or validation error
            L-->>C: e.g. constraint violation (unknown feature DN)
            C->>C: record "line N: reason", continue with next row
        end
    end

    alt no row failed
        C-->>R: SyncReport
        R->>FS: move to .done/yyyyMMdd-HHmmss-name.csv
    else any row failed
        C-->>R: IllegalStateException (good rows already applied)
        R->>FS: move to .failed/yyyyMMdd-HHmmss-name.csv
        Op->>FS: fix the file and drop it again
    end
```

## Who may hold what

| Folder | `acmeUserType` | Object classes added | Allowed grants |
|---|---|---|---|
| `internal/` | internal | `acmeFeatureAccess`, `acmeAccountAccess` | features, accounts |
| `members/` | member | `acmeMemberAccess` | policy numbers only |
| `other/` | other | all three | features, accounts, policy numbers |

Agents are never loaded from CSV: they come from Salesforce
([salesforce-sync.md](salesforce-sync.md)).
