#!/bin/sh
# Checks the directory behaviours the sync relies on, using throwaway entries
# (cn=ZZTEST* / uid=zztest*) that it removes again. Run against a fresh server:
#   LDAP_URI=ldap://127.0.0.1:1389 SVC_SYNC_PASSWORD=... SVC_AUTH_PASSWORD=... ./smoke-test.sh
# With LDAP_ADMIN_PASSWORD set it also checks OAuth client entries (only the
# rootdn creates those); without it those checks are skipped.
set -u
URI="${LDAP_URI:-ldap://127.0.0.1:1389}"
B=dc=acmeinsurance,dc=com
A=ou=agencies,ou=accounts,$B
F=ou=features,$B
OC=ou=oauth-clients,ou=services,$B
U=uid=zztest@agency.example,ou=agents,ou=people,$B
SYNC="-x -H $URI -D cn=svc-sync,ou=services,$B -w ${SVC_SYNC_PASSWORD:?}"
AUTH="-x -H $URI -D cn=svc-auth,ou=services,$B -w ${SVC_AUTH_PASSWORD:?}"
fails=0

ok()   { echo "PASS $1"; }
bad()  { echo "FAIL $1"; fails=$((fails + 1)); }
expect_rc() { # expect_rc <description> <wanted rc> <command...>
  d="$1"; want="$2"; shift 2
  "$@" >/dev/null 2>&1; rc=$?
  [ "$rc" = "$want" ] && ok "$d" || bad "$d (rc=$rc, wanted $want)"
}
grants() { ldapsearch $SYNC -b "$U" -s base -LLL -o ldif-wrap=no acmeAccountDN | grep '^acmeAccountDN' | sort | tr '\n' ' '; }
features() { ldapsearch $SYNC -b "$U" -s base -LLL -o ldif-wrap=no acmeFeatureDN | grep '^acmeFeatureDN' | sort | tr '\n' ' '; }

ldapadd $SYNC >/dev/null <<EOF
dn: cn=ZZTEST1,$A
objectClass: acmeAgencyAccount
objectClass: acmeSyncManaged
cn: ZZTEST1
acmeSalesforceId: 001ZZ0000000001AAA
acmeSource: salesforce

dn: cn=ZZTEST2,$A
objectClass: acmeAgencyAccount
objectClass: acmeSyncManaged
cn: ZZTEST2
acmeSalesforceId: 001ZZ0000000002AAA
acmeSource: salesforce

dn: cn=ZZTEST-AGR,cn=ZZTEST1,$A
objectClass: acmeAgreementAccount
objectClass: acmeSyncManaged
cn: ZZTEST-AGR
acmeSalesforceId: 001ZZ0000000003AAA
acmeSource: salesforce

dn: cn=ZZTEST-BK,cn=ZZTEST-AGR,cn=ZZTEST1,$A
objectClass: acmeBookAccount
objectClass: acmeSyncManaged
cn: ZZTEST-BK
acmeSalesforceId: 001ZZ0000000004AAA
acmeSource: salesforce

dn: $U
objectClass: inetOrgPerson
objectClass: acmeUser
objectClass: acmeAccountAccess
objectClass: acmeSyncManaged
uid: zztest@agency.example
cn: Zz Test
sn: Test
acmeUserType: agent
acmeSource: salesforce
acmeSalesforceId: 005ZZ0000000001AAA
acmeAccountDN: cn=ZZTEST-BK,cn=ZZTEST-AGR,cn=ZZTEST1,$A
userPassword: Smoke-Test-Pass-1
EOF
[ $? = 0 ] && ok "svc-sync creates agency > agreement > book and an agent" || bad "initial load"

reset_flag() { ldapsearch $SYNC -b "$U" -s base -LLL pwdReset | grep '^pwdReset'; }

# svc-sync has write (not manage) on userPassword, so its resets must pass the
# password policy, and ppolicy does not set pwdReset by itself. A provisioning
# reset therefore sets pwdReset: TRUE explicitly. (Resets by the rootdn get it
# automatically.)
expect_rc "ppolicy: svc-sync cannot set a password that fails the policy" 19 ldapmodify $SYNC <<EOF
dn: $U
changetype: modify
replace: userPassword
userPassword: short
EOF
expect_rc "ppolicy: svc-sync resets a password and sets pwdReset" 0 ldapmodify $SYNC <<EOF
dn: $U
changetype: modify
replace: userPassword
userPassword: Smoke-Test-Pass-Reset
-
replace: pwdReset
pwdReset: TRUE
EOF
# (WhoAmI is still allowed, so test with a search.)
expect_rc "ppolicy: after a reset, the user can only change their password" 50 \
  ldapsearch -x -H "$URI" -D "$U" -w Smoke-Test-Pass-Reset -b "$U" -s base
expect_rc "ppolicy: user with a reset password can change it" 0 ldappasswd -x -H "$URI" -D "$U" \
  -w Smoke-Test-Pass-Reset -a Smoke-Test-Pass-Reset -s Smoke-Test-Pass-2
[ -z "$(reset_flag)" ] && ok "ppolicy: the user's own change clears pwdReset" || bad "pwdReset still set: $(reset_flag)"

expect_rc "acl: a user cannot change their own user type" 50 ldapmodify -x -H "$URI" -D "$U" -w Smoke-Test-Pass-2 <<EOF
dn: $U
changetype: modify
replace: acmeUserType
acmeUserType: internal
EOF
before="$(grants)"
ldapmodify -x -H "$URI" -D "$U" -w Smoke-Test-Pass-2 >/dev/null 2>&1 <<EOF
dn: $U
changetype: modify
add: acmeAccountDN
acmeAccountDN: cn=ZZTEST2,$A
EOF
rc=$?
# Refused by constraint (19) before the ACL (50): the existence check runs as
# the user, who cannot see ou=accounts. Either way the grant must not change.
[ "$rc" != 0 ] && [ "$(grants)" = "$before" ] && ok "acl: a user cannot change their own grants" \
  || bad "user changed own grants (rc=$rc): $(grants)"

expect_rc "constraint: acmeUserType outside internal/agent/member/other is rejected" 19 ldapmodify $SYNC <<EOF
dn: $U
changetype: modify
replace: acmeUserType
acmeUserType: broker
EOF

expect_rc "unique: second entry with the same acmeSalesforceId is rejected" 19 ldapadd $SYNC <<EOF
dn: cn=ZZTEST-DUP,$A
objectClass: acmeAgencyAccount
cn: ZZTEST-DUP
acmeSalesforceId: 001ZZ0000000001AAA
EOF

expect_rc "constraint: grant to a non-existent account is rejected" 19 ldapmodify $SYNC <<EOF
dn: $U
changetype: modify
add: acmeAccountDN
acmeAccountDN: cn=NOPE,$A
EOF

ldapmodrdn $SYNC -s "cn=ZZTEST2,$A" "cn=ZZTEST-AGR,cn=ZZTEST1,$A" cn=ZZTEST-AGR >/dev/null 2>&1
case "$(grants)" in
  *"cn=ZZTEST-BK,cn=ZZTEST-AGR,cn=ZZTEST2,$A"*) ok "refint: moving an agreement subtree rewrites agents' grants" ;;
  *) bad "refint on move: $(grants)" ;;
esac

ldapmodify $SYNC >/dev/null 2>&1 <<EOF
dn: $U
changetype: modify
replace: pwdAccountLockedTime
pwdAccountLockedTime: 000001010000Z
-
replace: acmeDeactivated
acmeDeactivated: 20260101000000Z
EOF
expect_rc "ppolicy: locked agent cannot bind" 49 ldapwhoami -x -H "$URI" -D "$U" -w Smoke-Test-Pass-2
ldapmodify $SYNC >/dev/null 2>&1 <<EOF
dn: $U
changetype: modify
delete: pwdAccountLockedTime
-
delete: acmeDeactivated
EOF
expect_rc "ppolicy: unlocked agent can bind again" 0 ldapwhoami -x -H "$URI" -D "$U" -w Smoke-Test-Pass-2

expect_rc "acl: svc-auth cannot write" 50 ldapmodify $AUTH <<EOF
dn: cn=ZZTEST1,$A
changetype: modify
replace: description
description: x
EOF

ldapdelete $SYNC "cn=ZZTEST-BK,cn=ZZTEST-AGR,cn=ZZTEST2,$A" >/dev/null 2>&1
[ -z "$(grants)" ] && ok "refint: deleting a book removes the grant" || bad "refint on delete: $(grants)"

# Features: same pattern as accounts (constraint, refint, ACLs).
expect_rc "svc-sync creates a feature with two permissions and grants one to the agent" 0 ldapmodify $SYNC <<EOF
dn: cn=zztest-feat,$F
changetype: add
objectClass: acmeFeature
cn: zztest-feat

dn: cn=zz-read,cn=zztest-feat,$F
changetype: add
objectClass: acmePermission
cn: zz-read

dn: cn=zz-write,cn=zztest-feat,$F
changetype: add
objectClass: acmePermission
cn: zz-write

dn: $U
changetype: modify
add: objectClass
objectClass: acmeFeatureAccess
-
add: acmeFeatureDN
acmeFeatureDN: cn=zz-read,cn=zztest-feat,$F
EOF
expect_rc "constraint: acmeFeatureDN naming an account (not a feature) is rejected" 19 ldapmodify $SYNC <<EOF
dn: $U
changetype: modify
add: acmeFeatureDN
acmeFeatureDN: cn=ZZTEST1,$A
EOF
before="$(features)"
ldapmodify -x -H "$URI" -D "$U" -w Smoke-Test-Pass-2 >/dev/null 2>&1 <<EOF
dn: $U
changetype: modify
add: acmeFeatureDN
acmeFeatureDN: cn=zz-write,cn=zztest-feat,$F
EOF
rc=$?
[ "$rc" != 0 ] && [ "$(features)" = "$before" ] && ok "acl: a user cannot change their own feature grants" \
  || bad "user changed own feature grants (rc=$rc): $(features)"
n=$(ldapsearch $AUTH -b "cn=zztest-feat,$F" -LLL '(objectClass=acmePermission)' cn | grep -c '^cn:')
[ "$n" = 2 ] && ok "acl: svc-auth reads features and permissions" || bad "svc-auth sees $n permissions"
ldapdelete $SYNC "cn=zz-read,cn=zztest-feat,$F" >/dev/null 2>&1
[ -z "$(features)" ] && ok "refint: deleting a permission removes the grant" || bad "refint on feature delete: $(features)"
n=$(ldapsearch $AUTH -b "cn=sync,$F" -LLL '(objectClass=acmePermission)' cn 2>/dev/null | grep -c '^cn: run-sync$')
[ "$n" = 1 ] && ok "features: the backend's sync trigger permission (cn=run-sync,cn=sync) exists" \
  || bad "cn=run-sync,cn=sync,$F missing (38-sync-feature.ldif)"

# OAuth clients (rootdn only).
if [ -n "${LDAP_ADMIN_PASSWORD:-}" ]; then
  ADM="-x -H $URI -D cn=admin,$B -w $LDAP_ADMIN_PASSWORD"
  CS=zztest-client-secret-0123456789abcdef
  ldapadd $ADM >/dev/null <<EOF
dn: cn=zztest-conf,$OC
objectClass: acmeOAuthClient
cn: zztest-conf
acmeGrantType: password
userPassword: $CS
pwdPolicySubentry: cn=oauth-clients,ou=policies,$B

dn: cn=zztest-pub,$OC
objectClass: acmeOAuthClient
cn: zztest-pub
acmeRedirectUri: http://127.0.0.1:9/cb
acmeGrantType: authorization_code
EOF
  [ $? = 0 ] && ok "admin creates a confidential and a public OAuth client" || bad "OAuth client load"
  for i in 1 2 3 4 5 6; do ldapwhoami -x -H "$URI" -D "cn=zztest-conf,$OC" -w wrong >/dev/null 2>&1; done
  expect_rc "ppolicy: a client binds with its secret, even after 6 bad attempts (no lockout)" 0 \
    ldapwhoami -x -H "$URI" -D "cn=zztest-conf,$OC" -w "$CS"
  conf=$(ldapsearch $AUTH -b "cn=zztest-conf,$OC" -s base -LLL '(userPassword=*)' 1.1 | grep -c '^dn:')
  pub=$(ldapsearch $AUTH -b "cn=zztest-pub,$OC" -s base -LLL '(userPassword=*)' 1.1 | grep -c '^dn:')
  [ "$conf" = 1 ] && [ "$pub" = 0 ] && ok "acl: svc-auth can tell confidential from public clients" \
    || bad "client secret presence: confidential=$conf public=$pub"
  ldapsearch $AUTH -b "cn=zztest-conf,$OC" -s base -LLL userPassword | grep -q '^userPassword' \
    && bad "svc-auth can read a client secret" || ok "acl: svc-auth cannot read a client secret"
  ldapdelete $ADM "cn=zztest-conf,$OC" "cn=zztest-pub,$OC" >/dev/null 2>&1

  # Underwriting companies (maintained by the rootdn) and an agreement's
  # acmeUnderwritingCompanyDN. The agreement was moved under ZZTEST2 above.
  AGR="cn=ZZTEST-AGR,cn=ZZTEST2,$A"
  UWB=ou=underwritingCompanies,$B
  uw() { ldapsearch $SYNC -b "$AGR" -s base -LLL -o ldif-wrap=no acmeUnderwritingCompanyDN \
    | grep '^acmeUnderwritingCompanyDN' | sed 's/^[^:]*: //'; }
  expect_rc "admin creates an underwriting company" 0 ldapadd $ADM <<EOF
dn: cn=ZZTEST UW Co,$UWB
objectClass: acmeUnderwritingCompany
cn: ZZTEST UW Co
EOF
  n=$(ldapsearch $AUTH -b "$UWB" -s one -LLL '(objectClass=acmeUnderwritingCompany)' 1.1 | grep -c '^dn: cn=ZZTEST')
  [ "$n" = 1 ] && ok "acl: svc-auth reads underwriting companies" || bad "svc-auth sees $n test companies"
  expect_rc "svc-sync sets an agreement's underwriting company" 0 ldapmodify $SYNC <<EOF
dn: $AGR
changetype: modify
replace: acmeUnderwritingCompanyDN
acmeUnderwritingCompanyDN: cn=ZZTEST UW Co,$UWB
EOF
  expect_rc "constraint: an underwriting company DN that is not a company is rejected" 19 ldapmodify $SYNC <<EOF
dn: $AGR
changetype: modify
replace: acmeUnderwritingCompanyDN
acmeUnderwritingCompanyDN: cn=ZZTEST1,$A
EOF
  expect_rc "schema: only agreements hold an underwriting company" 65 ldapmodify $SYNC <<EOF
dn: cn=ZZTEST1,$A
changetype: modify
add: acmeUnderwritingCompanyDN
acmeUnderwritingCompanyDN: cn=ZZTEST UW Co,$UWB
EOF
  ldapmodrdn $ADM -r "cn=ZZTEST UW Co,$UWB" "cn=ZZTEST UW Co 2" >/dev/null 2>&1
  [ "$(uw)" = "cn=ZZTEST UW Co 2,$UWB" ] && ok "refint: renaming a company rewrites its agreements" \
    || bad "refint on company rename: $(uw)"
  ldapdelete $ADM "cn=ZZTEST UW Co 2,$UWB" >/dev/null 2>&1
  [ -z "$(uw)" ] && ok "refint: deleting a company clears its agreements" || bad "refint on company delete: $(uw)"
else
  echo "SKIP OAuth client checks (LDAP_ADMIN_PASSWORD not set)"
fi

# InsureDirectory's account (svc-directory), when its password is given.
if [ -n "${SVC_DIRECTORY_PASSWORD:-}" ]; then
  DIR="-x -H $URI -D cn=svc-directory,ou=services,$B -w $SVC_DIRECTORY_PASSWORD"
  UWB=ou=underwritingCompanies,$B
  expect_rc "svc-directory creates an underwriting company" 0 ldapadd $DIR <<EOF
dn: cn=ZZTEST DIR UW,$UWB
objectClass: acmeUnderwritingCompany
cn: ZZTEST DIR UW
EOF
  expect_rc "svc-directory deletes an underwriting company" 0 ldapdelete $DIR "cn=ZZTEST DIR UW,$UWB"
  expect_rc "acl: svc-sync cannot create an underwriting company" 50 ldapadd $SYNC <<EOF
dn: cn=ZZTEST SYNC UW,$UWB
objectClass: acmeUnderwritingCompany
cn: ZZTEST SYNC UW
EOF
  expect_rc "svc-directory resets a password and sets pwdReset" 0 ldapmodify $DIR <<EOF
dn: $U
changetype: modify
replace: userPassword
userPassword: Directory-Reset-Pass-3
-
replace: pwdReset
pwdReset: TRUE
EOF
  expect_rc "acl: svc-directory cannot add an OAuth client" 50 ldapadd $DIR <<EOF
dn: cn=zztest-dir-client,$OC
objectClass: acmeOAuthClient
cn: zztest-dir-client
EOF
  expect_rc "acl: svc-directory cannot change a service account's password" 50 ldapmodify $DIR <<EOF
dn: cn=svc-sync,ou=services,$B
changetype: modify
replace: userPassword
userPassword: Some-Other-Pass-123
EOF
else
  echo "SKIP svc-directory checks (SVC_DIRECTORY_PASSWORD not set)"
fi

# Clean up
ldapdelete $SYNC "$U" "cn=ZZTEST-AGR,cn=ZZTEST2,$A" "cn=ZZTEST1,$A" "cn=ZZTEST2,$A" \
  "cn=zz-write,cn=zztest-feat,$F" "cn=zztest-feat,$F" >/dev/null 2>&1
echo "$fails failure(s)"
[ "$fails" = 0 ]
