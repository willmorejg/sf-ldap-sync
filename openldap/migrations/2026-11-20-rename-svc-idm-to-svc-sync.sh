#!/bin/sh
# Renames the provisioning account cn=svc-idm to cn=svc-sync on a server built
# before 2026-11-20 (docs/LDAP_Schema_Definition.md calls it svc-sync). The entry keeps
# its password; afterwards rename SVC_IDM_PASSWORD to SVC_SYNC_PASSWORD in .env.
#
# Run inside the openldap container (needs ldapi:/// as root for cn=config):
#   podman cp openldap/migrations/2026-11-20-rename-svc-idm-to-svc-sync.sh sf-ldap-sync_openldap_1:/tmp/m.sh
#   podman exec -e LDAP_ADMIN_PASSWORD sf-ldap-sync_openldap_1 sh /tmp/m.sh
# Safe to run twice: it stops if svc-idm is already gone.
set -eu
B=dc=acmeinsurance,dc=com
OLD="cn=svc-idm,ou=services,$B"
ADM="-x -H ldap://127.0.0.1:1389 -D cn=admin,$B -w ${LDAP_ADMIN_PASSWORD:?}"
CFG="-Q -Y EXTERNAL -H ldapi:///"
DB="olcDatabase={1}mdb,cn=config"

if ! ldapsearch $ADM -b "$OLD" -s base -LLL 1.1 >/dev/null 2>&1; then
  echo "cn=svc-idm not found; nothing to do"
  exit 0
fi

# 1. ACLs and limits: replace every value, in order, with svc-idm -> svc-sync.
tmp=$(mktemp)
{
  echo "dn: $DB"
  echo "changetype: modify"
  for attr in olcLimits olcAccess; do
    echo "replace: $attr"
    ldapsearch $CFG -b "$DB" -s base -LLL -o ldif-wrap=no "$attr" \
      | grep "^$attr:" | sed 's/cn=svc-idm,/cn=svc-sync,/g'
    echo "-"
  done
} > "$tmp"
ldapmodify $CFG -f "$tmp" >/dev/null
rm -f "$tmp"
echo "cn=config: olcAccess and olcLimits now name cn=svc-sync"

# 2. The entry itself (keeps userPassword).
ldapmodrdn $ADM -r "$OLD" cn=svc-sync
ldapmodify $ADM >/dev/null <<L
dn: cn=svc-sync,ou=services,$B
changetype: modify
replace: description
description: Provisioning (the sync service); writes users, entitlements and accounts
L
echo "renamed $OLD to cn=svc-sync,ou=services,$B"
