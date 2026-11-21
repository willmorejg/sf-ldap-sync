# LDAP Schema Structure

## Overview

This schema defines user roles, access permissions, and organizational hierarchy for the Acme Insurance insurance platform. It enables fine-grained access control across different user types and organizational levels, ensuring each user can access only the policies, agreements, and accounts appropriate to their role.

---

## Table of Contents

**For Business Audiences:** Start with [User Types](#user-types), [Organizational Hierarchy](#organizational-hierarchy), and [Common Scenarios](#common-scenarios-user-types--roles-in-action).

**For Technical Audiences:** Start with [User Types](#user-types) for context, then see [Schema Implementation & Technical Details](#schema-implementation--technical-details), [Quick Reference](#quick-reference-access-pattern-by-role), and [Data Synchronization](#data-synchronization).

**For Everyone:** [Roles](#roles) and [Glossary](#glossary) provide reference material.

- [User Types](#user-types) — Four user classifications and their access patterns
  - [Member](#member)
  - [Agent](#agent)
  - [Internal](#internal)
  - [Other](#other)
- [Roles](#roles) — Features and capabilities tied to users
  - [Role vs. User Type](#role-vs-user-type)
  - [Common Roles](#common-roles)
  - [Technical Implementation](#technical-implementation)
- [Organizational Hierarchy](#organizational-hierarchy) — Account structure (agencies, agreements, books)
- [Policies](#policies) — How policies link users to coverage
- [Schema Implementation & Technical Details](#schema-implementation--technical-details) — LDAP schema, attributes, access rules
  - [Object Classes](#object-classes)
  - [Key Attributes](#key-attributes)
  - [Access Control Rules (Technical)](#access-control-rules-technical)
- [Quick Reference](#quick-reference-access-pattern-by-role) — At-a-glance access patterns
- [Data Synchronization](#data-synchronization) — How data flows into the directory
- [Security & Compliance](#security--compliance) — Design principles
- [Common Scenarios](#common-scenarios-user-types--roles-in-action) — Real-world examples
- [Summary Table](#summary-table-when-ldap-is-read-vs-modified) — LDAP operations matrix
- [Glossary](#glossary) — Term definitions

---

## User Types

The system supports four distinct user types, each with different access patterns and use cases. User type determines **who can access what**, while role (see [Roles](#roles) section) determines **what they can do with it**.

### Member

**Who They Are:** Policyholders and insureds—end customers who have purchased insurance from Acme Insurance.

**Business Context:**
- Policy owners or named insureds on policies
- Access primarily for self-service needs: view policies, download documents, file claims, update contact information
- Never have administrative privileges

**Technical Profile:**
- **Object Class:** `acmeUser` (auxiliary: `acmeMemberAccess`)
- **User Type Identifier:** `acmeUserType=member`
- **Access Grants:** `acmeMemberAccess` (holds `acmePolicyNumber` values)
- **Source:** Typically CSV import or manual provisioning; never from Salesforce
- **External Only:** No internal network access

**Permissions:**
- Read-only access to policies in their portfolio (direct or as named insured)
- Specific grants limited by policy number; no account hierarchy visibility
- Cannot access books, agreements, or agencies
- Cannot access other members' policies

**Example:** Jane Doe holds policy #POL-2024-98765 with Acme Insurance; she logs in to view coverage details, download her declarations page, and report a claim.

---

### Agent

**Who They Are:** Licensed insurance professionals (brokers, agents, adjusters) who represent Acme Insurance's business partners.

**Business Context:**
- Sell and service policies on behalf of an agency
- Manage multiple "books of business" (portfolios of policies)
- Access needed for quoting, issuing, servicing, and reporting
- May work for a brokerage, MGA, or directly represent Acme Insurance

**Technical Profile:**
- **Object Class:** `acmeUser` (auxiliary: `acmeAccountAccess`)
- **User Type Identifier:** `acmeUserType=agent`
- **Access Grants:** `acmeAccountDN` (holds DNs of agencies, agreements, and books)
- **Source:** Salesforce sync (via Salesforce User records); can be supplemented manually
- **External Only:** Remote/field access; no internal network requirement

**Permissions:**
- Access to 1 or more books of business across agreements and agencies
- View related agreements and agencies (hierarchical scope)
- Create and manage policies within their books
- Quote policies using book data
- Assign third-party services (e.g., Lexis Nexis) at the book level
- Customize reporting scoped to book, agreement, and/or agency
- Cannot access policies outside their granted books
- Cannot modify agency or agreement configuration

**Example:** Bob Smith works for Premier Insurance Brokerage. He's granted access to 3 books under "Commercial Auto" agreement within "Premier–West Region" agency. He can quote and issue auto policies from those books, but cannot touch his colleague's Commercial Property books.

---

### Internal

**Who They Are:** Acme Insurance employees or authorized contractors performing administrative, operational, or analytical work.

**Business Context:**
- May work in customer service, claims, underwriting, compliance, finance, or operations
- Access determined by job function and responsibility
- May need access to company-wide systems, regional data, or specific accounts
- Authentication via corporate credentials

**Technical Profile:**
- **Object Class:** `acmeUser` (auxiliary: `acmeFeatureAccess`; optionally `acmeAccountAccess`)
- **User Type Identifier:** `acmeUserType=internal`
- **Access Grants:** `acmeFeatureDN` (primary: holds feature/role DNs); optionally `acmeAccountDN` (secondary scoping)
- **Source:** Manual provisioning or CSV import; never from Salesforce
- **Network Scope:** Internal corporate network (may also access externally via VPN/secure link)

**Permissions:**
- Access determined primarily by feature-based RBAC grants (e.g., "CSR," "Underwriter," "Finance Manager")
- Features can include optional account scoping: e.g., "CSR for West Region" = `acmeFeatureDN` (CSR role) + `acmeAccountDN` (West Region agency)
- Broad data access within feature scope; no policy-level restrictions
- Can perform administrative tasks (modify accounts, adjust grants, generate reports)
- Exact capabilities depend on feature/role; applications enforce feature-level permissions

**Example:** Alice works in Claims. She's granted the "Claims Adjuster" feature, which allows her to review all claims, request information, and approve/deny. She's also scoped to agencies in her region, limiting claim visibility to that geography.

---

### Other

**Who They Are:** Provisional, temporary, or special-purpose users whose role isn't yet determined or doesn't fit standard categories.

**Business Context:**
- Temporary contractors, consultants, or vendors
- Users in onboarding flow awaiting proper classification
- Pilot/sandbox participants testing new functionality
- Ad-hoc users with time-limited access

**Technical Profile:**
- **Object Class:** `acmeUser` (auxiliary: flexible—`acmeFeatureAccess`, `acmeAccountAccess`, or `acmeMemberAccess`)
- **User Type Identifier:** `acmeUserType=other`
- **Access Grants:** Flexible; determined case-by-case during onboarding
- **Source:** Manual provisioning; can migrate from CSV
- **Network Scope:** Determined per user

**Permissions:**
- None by default; all access is explicit and assigned during provisioning
- May receive feature grants (like Internal users), account grants (like Agents), or policy grants (like Members)
- Typically time-limited or project-specific
- Easily deactivated without data loss

**Example:** Chris is a third-party auditor hired to review claims processes. He's granted read-only access to the "Auditor" feature for 90 days, scoped to a specific agency. When the audit completes, his access is deactivated.

---

## Roles

**Definition:** Roles are collections of application capabilities grouped under a feature or permission. They define **what a user can do** (the actions they can perform), independent of **what they can access** (which is determined by user type and grants).

### Role vs. User Type

| Aspect | User Type | Role |
|---|---|---|
| **Determines** | Access scope (who can see what) | Capabilities (what can be done) |
| **Stored In LDAP** | `acmeUserType` attribute | `acmeFeatureDN` attribute (feature grants) |
| **Example** | `member`, `agent`, `internal`, `other` | `csr`, `underwriter`, `claims-adjuster`, `auditor` |
| **Hierarchy** | Mutually exclusive (one per user) | Multi-valued (user can hold multiple roles) |
| **Managed By** | Provisioning flow (sync, CSV, manual) | Application configuration or manual grant |

### Common Roles

The following are typical roles for Acme Insurance systems. Applications define exactly which roles they support and what permissions each provides:

#### Customer Service Representative (CSR)

**Who:** Acme Insurance customer service team members

**Capabilities (examples):**
- Look up customer and policy information
- Create new policies on behalf of members
- Update policy details and contact information
- Process policy cancellations
- Handle basic inquiries and documentation requests
- Run standard reports scoped to assigned region/agency

**Access Scoping:** Often scoped to specific agencies or regions

**Example Implementation (LDAP):**
```
acmeFeatureDN: cn=csr,ou=features,o=acmeinsurance
acmeAccountDN: cn=west-region,ou=agencies,o=acmeinsurance  (optional scope)
```

---

#### Underwriter

**Who:** Acme Insurance underwriting team

**Capabilities (examples):**
- Review policy applications
- Request additional information from agents/members
- Approve or decline policy issuance
- Set terms, conditions, and premium
- Access underwriting rules and rating tables

**Access Scoping:** May be scoped by product line (agreement) or region

**Example Implementation (LDAP):**
```
acmeFeatureDN: cn=underwriter,ou=features,o=acmeinsurance
acmeAccountDN: cn=commercial-auto,ou=agreements,cn=west-region,ou=agencies,o=acmeinsurance
```

---

#### Claims Adjuster

**Who:** Claims team member

**Capabilities (examples):**
- Review claim submissions
- Coordinate with insureds, agents, and service vendors
- Approve claim payments
- Manage claim lifecycle (open, investigate, close)
- Generate claim reports and analytics

**Access Scoping:** Typically scoped by region or claim type

**Example Implementation (LDAP):**
```
acmeFeatureDN: cn=claims-adjuster,ou=features,o=acmeinsurance
acmeAccountDN: cn=west-region,ou=agencies,o=acmeinsurance
```

---

#### Compliance Officer

**Who:** Risk, compliance, or regulatory team

**Capabilities (examples):**
- Access all accounts and policies for audit
- Run compliance reports and generate audit trails
- Review user access and system logs
- Verify policy compliance with regulatory requirements

**Access Scoping:** Typically no account scoping (org-wide access)

**Example Implementation (LDAP):**
```
acmeFeatureDN: cn=compliance-officer,ou=features,o=acmeinsurance
(no acmeAccountDN scoping)
```

---

#### Finance Manager

**Who:** Finance, accounting, or revenue team

**Capabilities (examples):**
- Access billing and commission data
- Run financial reports and analytics
- Review transaction history
- Export data for accounting systems
- Modify payment terms (varies by org policy)

**Access Scoping:** May be scoped by agency or business unit

**Example Implementation (LDAP):**
```
acmeFeatureDN: cn=finance-manager,ou=features,o=acmeinsurance
acmeAccountDN: cn=partner-agencies,ou=agencies,o=acmeinsurance  (optional)
```

---

### How Roles Interact with User Types

**Internal Users + Roles:**
- Roles define what an internal employee can do
- Roles are stored as `acmeFeatureDN` grants
- Example: Internal user Jane has role "CSR" → can perform CSR actions in the application

**Agent Users + Roles:**
- Agents rarely hold explicit "role" grants; their access is scoped by `acmeAccountDN` (which books they access)
- Agents implicitly have an "agent" capability set (quote, issue, service policies)
- Special agents (e.g., underwriters, compliance agents) may hold additional feature grants

**Member Users + Roles:**
- Members do not hold roles; they have policy grants (`acmeMemberAccess`)
- Member capabilities are static: view policy, update contact info, file claim (no RBAC variation)

**Other Users + Roles:**
- Determined on case-by-case basis during onboarding
- May be provisioned with any combination of roles and access grants

### Technical Implementation

**LDAP Storage:**
- Features (roles) are stored as entries under `ou=features,o=acmeinsurance`
- Each feature has a DN, e.g., `cn=csr,ou=features,o=acmeinsurance`
- Users hold grants via `acmeFeatureDN` multi-valued attribute
- Example:
  ```
  dn: uid=jane.smith,ou=people,ou=internal,o=acmeinsurance
  acmeFeatureDN: cn=csr,ou=features,o=acmeinsurance
  acmeFeatureDN: cn=trainer,ou=features,o=acmeinsurance
  acmeAccountDN: cn=west-region,ou=agencies,o=acmeinsurance
  ```

**Application Enforcement:**
- Applications read `acmeFeatureDN` values from the user's LDAP entry at login
- Application checks feature DN against its access control list (ACL) for each operation
- Example: When Jane clicks "Approve Policy," the app checks if `cn=underwriter,ou=features,o=acmeinsurance` is in her `acmeFeatureDN` list
- Applications may also read `acmeAccountDN` and enforce geographic/organizational scoping

**No Automatic Enforcement in LDAP:**
- LDAP directory stores role grants; it does not enforce them
- Applications are responsible for checking `acmeFeatureDN` values and denying unauthorized actions
- This design allows applications to evolve role definitions without changing the directory schema

---

---

## Organizational Hierarchy

Acme Insurance's business structure maps to an account hierarchy, mirroring how the company organizes insurance products:

```
Agency (Broker/MGA)
  ├── Agreement (Contract/Product Line) ··· underwriting company
  │   ├── Book (Subset of policies)
  │   └── Book
  └── Agreement
      ├── Book
      └── Book
```

### Agency

A broker or managing general agent (MGA) representing the top level of the organizational structure. Agencies are synced from Salesforce Account records (Account record type).

### Agreement

A contract or product line within an agency (e.g., "Commercial Auto," "Workers Compensation"). One agency typically has multiple agreements. Agreements are synced from Salesforce Account records (Agreement record type) and are linked to agencies via `ParentId`.

Each agreement may name its **underwriting company** in `acmeUnderwritingCompanyDN`
(single-valued). The value is the DN of an `acmeUnderwritingCompany` entry, kept in the
directory like the account hierarchy:

```
ou=underwritingCompanies,dc=acmeinsurance,dc=com
├── cn=Big Insurance UW Co
└── cn=Second Mutual Underwriters
```

The companies are maintained by hand (cn=admin or phpLDAPadmin), and are readable by
svc-sync and svc-auth. The constraint overlay accepts only DNs of companies directly under
`ou=underwritingCompanies`. Refint rewrites the value when a company is renamed, and
removes it when the company is deleted. The schema allows the attribute only on agreements.
The Salesforce sync sets it from an agreement field when
`sync.accounts.underwriting-company-field` is configured, and otherwise leaves it alone.
Access tokens carry it in `accounts.underwriting_companies`, a map from agreement path to company `cn`. The map
covers every agreement the user's grants reach: held agreements, the agreement above a held book, and the
agreements directly under a held agency.

### Book

A portfolio of policies within an agreement (e.g., "2024 Commercial Auto - Northeast Region"). Each agreement contains one or more books; books are where actual policy data lives and agents have direct access. Books are synced from Salesforce Account records (Book record type) and are linked to agreements via `ParentId`.

### Client

A client account represents an internal business entity or partner organization managed outside the Salesforce hierarchy (e.g., third-party administrators, affiliate partners, or carrier entities). Clients live under `ou=clients,ou=accounts` and are not part of the agency > agreement > book tree. They are created and managed manually or through CSV import, not from Salesforce. Clients use `acmeClientAccount` object class.

---

## Policies

**Business Purpose:** Policies are insurance contracts linking insureds (members) to specific coverage under a book. Each policy tracks:

- The **insured** (member/policyholder) who owns the coverage
- The **issuing agent** who sold or manages the policy
- The **quoting agent** who originated the policy application

**Access Rules:**

- **Members** can view their own policies within their portfolio
- **Agents** can view and manage policies in books they're granted access to
- **Internal users** can access any policy based on their feature-level grants (e.g., "CSR," "Claims Adjuster," "Underwriter")
- **Other users** receive case-by-case grants during onboarding

---

## Features and Permissions

**Definition:** Features are application capabilities grouped hierarchically. Features organize permissions, and both can be granted to users via `acmeFeatureDN`.

### Feature Hierarchy

Features live in a tree under `ou=features,dc=acmeinsurance,dc=com`:

```
ou=features
├── cn=quoting
│   ├── cn=read-quotes
│   └── cn=write-quotes
├── cn=claims
│   ├── cn=view-claims
│   ├── cn=adjust-claims
│   └── cn=approve-claims
└── cn=underwriting
    ├── cn=review-applications
    └── cn=approve-policies
```

**Granting:** A user's `acmeFeatureDN` can point to:
1. A **feature** (e.g., `cn=quoting,ou=features,dc=acmeinsurance,dc=com`) → grants all permissions under it
2. A **permission** (e.g., `cn=read-quotes,cn=quoting,ou=features,dc=acmeinsurance,dc=com`) → grants just that permission

**Expansion:** At login, the auth service expands feature grants into their contained permissions. If a permission is added to a feature later, all users granted that feature automatically get the new permission.

**Enforcement:** Applications read `acmeFeatureDN` values and check them against their access control rules. The directory stores grants; the application enforces capabilities.

### OAuth Clients

OAuth clients (browser apps, mobile apps, backend services) are registered under `ou=oauth-clients,ou=services,dc=acmeinsurance,dc=com`.

**Client Types:**

| Type | Characteristics | Grant Types | Secret |
|---|---|---|---|
| **Public Client** | Browser or mobile app; runs on untrusted device | `authorization_code` (with PKCE) | None |
| **Confidential Client** | Backend service; runs in trusted environment | `authorization_code`, `password`, `refresh_token` | `userPassword` (hashed as client secret) |

**Public Client Example:**
```
dn: cn=acmeinsurance-webapp,ou=oauth-clients,ou=services,dc=acmeinsurance,dc=com
objectClass: acmeOAuthClient
cn: acmeinsurance-webapp
acmeGrantType: authorization_code
acmeRedirectUri: https://app.acmeinsurance.com/auth/callback
acmeRedirectUri: https://app.acmeinsurance.com/auth/callback2
displayName: Acme Insurance Web Application
```

**Confidential Client Example:**
```
dn: cn=acmeinsurance-mobile-backend,ou=oauth-clients,ou=services,dc=acmeinsurance,dc=com
objectClass: acmeOAuthClient
cn: acmeinsurance-mobile-backend
acmeGrantType: authorization_code
acmeGrantType: refresh_token
acmeRedirectUri: https://backend.acmeinsurance.com/auth/callback
userPassword: {ARGON2}$argon2id$... (client secret, hashed by slapd)
displayName: Acme Insurance Mobile Backend
```

---

## Schema Implementation & Technical Details

### Object Classes

The LDAP schema uses the following principal object classes to implement the structure above:

| Object Class | Purpose | Extends |
|---|---|---|
| `acmeUser` | Base user class (auxiliary) | — |
| `acmeAccount` | Base for all account hierarchy items | `top` (structural) |
| `acmeAgencyAccount` | A broker or MGA | `acmeAccount` |
| `acmeAgreementAccount` | A contract or product line | `acmeAccount` |
| `acmeBookAccount` | A policy portfolio | `acmeAccount` |
| `acmeClientAccount` | A client (internally generated ID, outside agency tree) | `acmeAccount` |
| `acmeUnderwritingCompany` | An underwriting company; the possible values of an agreement's `acmeUnderwritingCompanyDN` | `top` (structural) |
| `acmeAccountAccess` | Auxiliary: access grants to accounts | — |
| `acmeMemberAccess` | Auxiliary: access grants to policies (members) | — |
| `acmeFeatureAccess` | Auxiliary: access grants to features (employees) | — |
| `acmeSyncManaged` | Auxiliary: marks entries managed by sync automation | — |
| `acmeFeature` | A feature (application role); parent of permissions | `top` (structural) |
| `acmePermission` | A permission within a feature | `top` (structural) |
| `acmeOAuthClient` | An OAuth 2.0 application registration | `top` (structural) |

### Key Attributes

**User-Level Attributes:**
- `uid` – Unique identifier (e.g., username, email)
- `acmeUserType` – Enum: `internal`, `agent`, `member`, `other` (enforced by constraint overlay)
- `acmeSource` – Origin of record: `salesforce`, `csv`, `manual`
- `acmeDeactivated` – Generalized time value when user is locked or archived
- `acmeAccountDN` – DNs of accounts (agencies, agreements, books) the user can access
- `acmeFeatureDN` – DNs of features or permissions the user is granted (RBAC)
- `acmeMemberAccess` – Policy/membership grants (holds `acmePolicyNumber` values)
- `pwdAccountLockedTime` – Set when account is locked by password policy

**Account-Level Attributes:**
- `cn` – Common name, typically derived from account number or business name
- `acmeSalesforceId` – ID of the corresponding Salesforce record (case-sensitive)
- `acmeSource` – Origin of record
- `displayName` – Human-readable name
- `description` – Account description
- `acmeUnderwritingCompanyDN` – Agreements only: DN of the underwriting company (single-valued)
- `userPassword` – (For OAuth clients only; confidential clients have secrets)

**Feature & Permission Attributes:**
- `cn` – Feature or permission name (required)
- `displayName` – Human-readable display name
- `description` – Feature or permission description

**OAuth Client Attributes:**
- `cn` – Client ID (required)
- `userPassword` – Client secret (confidential clients only; hashed by slapd)
- `acmeRedirectUri` – Registered OAuth redirect URI (multi-valued, case-sensitive)
- `acmeGrantType` – OAuth grant type(s) the client may use:
  - `authorization_code` (browser/SPA with PKCE)
  - `password` (backend service)
  - `refresh_token` (refresh token grant)
- `displayName` – Application name
- `description` – Application description

### Access Control Rules (Technical)

**Safety Guards:**
- Agents can only modify `acmeAccountDN` values under `ou=agencies` (not client grants)
- Sync automation only modifies entries where `acmeSource` matches the sync source
- Hand-made entries (no `acmeSource` or different `acmeSource`) are protected unless `sync.safety.adopt-unowned=true`
- Empty syncs and over-removal are blocked by `SafetyGuard` (max `max-remove-percent` per run)

**Password & Authentication:**
- Passwords are never written by sync automation
- Resets must set `pwdReset: TRUE` in the same LDAP operation
- Password policy (`ppolicy`) remains authoritative; auth service only reads
- Locked users have `pwdAccountLockedTime=000001010000Z` plus `acmeDeactivated=TRUE`

**Cascading Operations:**
- Accounts deleted children-first; referential integrity (`refint`) cleans up orphaned grants
- Deactivated users remain in directory (never deleted) for audit trails

---

## Quick Reference: Access Pattern by Role

| User Type | Key Directory Attributes | Access Determined By | Management |
|---|---|---|---|
| **Member** | `acmeUserType=member`, `acmeMemberAccess` | Policy grants (`acmePolicyNumber` list) | Manual or CSV import |
| **Agent** | `acmeUserType=agent`, `acmeAccountDN` | Account grants (`acmeAccountDN` list under agencies) | Salesforce sync or manual |
| **Internal** | `acmeUserType=internal`, `acmeFeatureDN` | Feature grants (`acmeFeatureDN` list) + optional account scoping | Manual, CSV, or feature assignment |
| **Other** | `acmeUserType=other`, flexible | Case-by-case grants via features, accounts, or policies | Determined per user during onboarding |

---

## Data Synchronization

**Salesforce Sync** (`sync` container):
- Pushes Agency, Agreement, and Book records as `acmeAgencyAccount`, `acmeAgreementAccount`, `acmeBookAccount` entries
- Creates or updates Agent users from Salesforce Users; sets `acmeAccountDN` grants from account relationships
- Marks all entries with `acmeSource=salesforce` and `acmeSyncManaged=TRUE`
- **Does not:** touch client/feature grants, passwords, or hand-made entries

**CSV Import:**
- Loads Internal, Member, and Other users from drop folders (`inbox/{internal,members,other}`)
- Marks entries with `acmeSource=csv` and `acmeSyncManaged=TRUE`
- Updates existing entries; creates new ones as needed

**Manual Operations:**
- Entries created outside sync/CSV have no `acmeSource` or have `acmeSource=manual`
- Protected from automation unless explicitly adopted
- Useful for testing, one-off grants, and administrative overrides

---

## Security & Compliance

- **Referential Integrity:** Deleting an account automatically cleans up all associated grants (`acmeAccountDN` values)
- **Audit Trail:** Deactivated users remain in directory; never deleted. `acmeDeactivated` and `pwdAccountLockedTime` provide clear markers
- **Role Separation:** Sync service runs as `svc-sync` (modify accounts, users, grants); Auth service runs as `svc-auth` (read-only, plus password verification via user bind)
- **Policy Enforcement:** ACLs enforce role-based access; sync automation respects source ownership and safety thresholds

---

## Common Scenarios: User Types + Roles in Action

This section shows how user types and roles work together in real-world situations.

### Scenario 1: Agent Quoting a Policy

**Actors & System Flow:**

1. **Agent Bob** (user type: `agent`, source: Salesforce)
   - `acmeAccountDN` includes: `cn=commercial-auto-2024,ou=agreements,cn=premier-brokers,ou=agencies,o=acmeinsurance`
   - No feature grants (Bob's access is fully scoped by his agency/agreement/book grants)

2. **Bob logs in** → Auth service checks:
   - Is Bob's `pwdAccountLockedTime` unset? (Not locked) ✓
   - Is Bob's `acmeDeactivated` unset? (Not deactivated) ✓
   - Is Bob's password valid? ✓
   - Return Bob's `acmeAccountDN` list to the application

3. **Bob navigates to "Quote Policy"** → Application checks:
   - Does Bob have access to any books? (Yes, via `acmeAccountDN`) ✓
   - Which policies can Bob view? (Those in his granted books) ✓
   - Application loads policy templates, rates, and risk data for Bob's books

4. **Bob quotes and issues a policy** → System creates the policy:
   - Linked to Bob's book (referential integrity preserved)
   - Marked with `acmeSource=internal` or `acmeSource=system` (not Bob's responsibility)
   - Bob is recorded as the quoting/issuing agent

**LDAP Changes:** None directly by Bob's action; the policy is created in the application, not the directory.

---

### Scenario 2: CSR Helping a Member

**Actors & System Flow:**

1. **Member Alice** (user type: `member`, source: CSV)
   - `acmeMemberAccess` includes: `acmePolicyNumber=POL-2024-11111`
   - No other grants

2. **CSR Manager Carol** (user type: `internal`, source: Manual provisioning)
   - `acmeFeatureDN` includes: `cn=csr,ou=features,o=acmeinsurance`
   - `acmeAccountDN` includes: `cn=west-region,ou=agencies,o=acmeinsurance` (scope)

3. **Alice logs in** → Auth service returns:
   - Alice's policy list (from `acmeMemberAccess`)
   - Application loads only Alice's policies

4. **Alice calls customer service** → Carol logs in:
   - Auth service returns: Carol has "CSR" feature + "West Region" account scope
   - Application loads all policies from West Region agencies

5. **Carol looks up Alice's account**:
   - Application queries: "Is Alice's policy in West Region?" (Yes, via business logic)
   - Carol can now see Alice's policy and help process a claim request

6. **Carol updates Alice's contact info** → System records:
   - Update attributed to Carol's user ID and timestamp
   - Audit trail shows the change

**LDAP Changes:** None for Alice or Carol (directory is read-only by apps); the policy or member record is updated in the application.

---

### Scenario 3: Underwriter Approving a Policy

**Actors & System Flow:**

1. **Underwriter Uma** (user type: `internal`, source: Manual provisioning)
   - `acmeFeatureDN` includes: `cn=underwriter,ou=features,o=acmeinsurance`
   - `acmeAccountDN` includes: `cn=commercial-auto,ou=agreements,cn=premier-brokers,ou=agencies,o=acmeinsurance`

2. **Uma logs in** → Auth service returns:
   - Uma has "Underwriter" feature
   - Uma's scope: Commercial Auto agreement under Premier Brokers agency
   - Application loads policies in that scope awaiting underwriting

3. **Uma reviews a policy** quoted by Agent Bob:
   - Application checks: "Is Uma's 'underwriter' feature active?" (Yes) ✓
   - Application checks: "Is this policy in Uma's scoped agreement?" (Yes) ✓
   - Uma can review risk, set terms, approve, or request more information

4. **Uma approves the policy** → System records:
   - Approval timestamp and Uma's user ID
   - Policy status changes to "Issued"
   - Audit trail created

**LDAP Changes:** None directly; the directory stores Uma's permissions, but the application makes the authorization decision.

---

### Scenario 4: Deactivating an Agent After Termination

**Actors & System Flow:**

1. **Agent Dave** (user type: `agent`, source: Salesforce)
   - Currently active with `acmeAccountDN` grants for 3 books
   - `acmeDeactivated` is unset
   - `pwdAccountLockedTime` is unset

2. **Dave is terminated** → HR notifies the sync system:
   - Salesforce User record for Dave is deactivated (IsActive = false)

3. **Sync automation runs** (Salesforce sync):
   - Reads Salesforce Users
   - Dave is not in the active user list
   - Sync finds Dave's LDAP entry (`uid=dave.xxx,...`)
   - Sync sets `acmeDeactivated=TRUE` and `pwdAccountLockedTime=000001010000Z`
   - Dave's `acmeAccountDN` grants remain (for audit trail)
   - Dave's entry is marked with `acmeSource=salesforce` and `acmeSyncManaged=TRUE`

4. **Dave attempts to log in** → Auth service checks:
   - Is Dave's `pwdAccountLockedTime` set? (Yes, locked) ✗
   - Auth service returns: "Account locked"
   - Dave is denied access

5. **Later, compliance audits account changes** → Audit system queries:
   - `acmeDeactivated=TRUE` entries in `ou=agents,ou=people`
   - Finds Dave with timestamp/reason in audit logs
   - Confirms Dave is no longer active but his grants are preserved for historical reference

**LDAP Changes:**
- Dave's entry updated: `acmeDeactivated=TRUE`, `pwdAccountLockedTime=000001010000Z`
- Dave is **not deleted**; deactivation is reversible if needed

---

### Scenario 5: Onboarding a Contractor ("Other" User)

**Actors & System Flow:**

1. **Contractor Casey** is hired for a 3-month project:
   - System creates a new user with `acmeUserType=other`
   - No features or account grants yet

2. **HR specifies access needs**:
   - Casey needs to review and analyze claims data for one specific agency
   - Casey needs read-only (no approval/modification) capabilities

3. **Provisioning creates Casey's LDAP entry**:
   - `uid=casey.xxx,...`
   - `acmeUserType=other`
   - `acmeFeatureDN=cn=claims-analyst-readonly,ou=features,o=acmeinsurance`
   - `acmeAccountDN=cn=west-agency,ou=agencies,o=acmeinsurance`
   - `acmeSource=manual` (not synced; not automated)

4. **Casey logs in** → Auth service returns:
   - Casey has "claims-analyst-readonly" feature + "West Agency" scope
   - Application checks: Casey can read claims but cannot approve or modify them

5. **Project ends** → HR requests deactivation:
   - System sets `acmeDeactivated=TRUE` on Casey's entry
   - Casey cannot log in
   - After 90 days, Casey's entry can be deleted or archived per retention policy

**LDAP Changes:**
- Casey's entry created with temporary source (`acmeSource=manual`)
- Casey is never touched by sync automation (source doesn't match)
- Deactivation is straightforward; no data corruption risk

---

## Summary Table: When LDAP is Read vs. Modified

| Event | User Type | LDAP Read | LDAP Modified | Source |
|---|---|---|---|---|
| User logs in | All | ✓ (read attributes) | — | Auth service |
| Agent views books | Agent | ✓ (read `acmeAccountDN`) | — | Application |
| CSR helps member | Internal + Member | ✓ (read features, `acmeAccountDN`) | — | Application |
| Agent terminated | Agent | — | ✓ (set `acmeDeactivated`, `pwdAccountLockedTime`) | Salesforce sync |
| New member added | Member | — | ✓ (create entry, `acmeMemberAccess`) | CSV import or manual |
| Feature granted | Internal | — | ✓ (add `acmeFeatureDN`) | Manual provisioning |
| Book created | — | — | ✓ (create `acmeBookAccount` entry) | Salesforce sync |
| Policy approved | — | — | — (policy is in app DB, not LDAP) | Application |

---

## Glossary

| Term | Definition | LDAP Attribute/Class |
|---|---|---|
| **User Type** | Classification determining access scope: member, agent, internal, or other | `acmeUserType` |
| **Role** | Collection of capabilities/features; defines what a user can do | `acmeFeatureDN` (holds feature DNs) |
| **Feature** | A named capability or role (e.g., "CSR," "Underwriter"); stored as an entry under `ou=features` | `cn=feature-name,ou=features,o=acmeinsurance` |
| **Account Grant** | Permission to access a book, agreement, or agency | `acmeAccountDN` |
| **Policy Grant** | Permission to view/manage a specific policy | `acmeMemberAccess` (holds `acmePolicyNumber`) |
| **Deactivated** | User is locked and cannot log in; entry retained for audit | `acmeDeactivated=TRUE` + `pwdAccountLockedTime` set |
| **Synced Entry** | LDAP entry managed by automation (Salesforce sync or CSV import) | `acmeSyncManaged=TRUE` + `acmeSource=salesforce\|csv` |
| **Manual Entry** | LDAP entry created outside automation; not touched by sync | `acmeSource=manual` or unset |
| **Source** | Origin of the LDAP entry: Salesforce, CSV, manual, or system | `acmeSource` |
| **Book** | Portfolio of policies within an agreement; agents access books | `acmeBookAccount` (object class) |
| **Agreement** | Contract or product line within an agency | `acmeAgreementAccount` (object class) |
| **Agency** | Top-level account: broker or MGA | `acmeAgencyAccount` (object class) |

---