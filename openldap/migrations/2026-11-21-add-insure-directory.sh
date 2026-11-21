#!/bin/sh
# Adds what the InsureDirectory app (frontend/) needs to a server built before
# 2026-11-21:
#   - the cn=svc-directory service account (password from SVC_DIRECTORY_PASSWORD)
#   - the ACLs and limits of the current 10-config-db.ldif (they name svc-directory)
#   - the "directory" feature and its permissions (36-insure-directory.ldif)
#   - the insure-directory OAuth client, when DIRECTORY_CLIENT_SECRET is set
#     (37-oauth-insure-directory.ldif)
#
# Rebuild the openldap image first (the script reads /seed from it), recreate the
# container (volumes are kept), then run it inside the container:
#   podman cp openldap/migrations/2026-11-21-add-insure-directory.sh sf-ldap-sync_openldap_1:/tmp/m.sh
#   podman exec -e LDAP_ADMIN_PASSWORD -e SVC_DIRECTORY_PASSWORD -e DIRECTORY_CLIENT_SECRET \
#     sf-ldap-sync_openldap_1 sh /tmp/m.sh
# Safe to run twice: each step checks whether it is already done. Step 2 replaces
# every olcAccess and olcLimits value, so local ACL changes not in
# 10-config-db.ldif are lost; compare first if you have made any.
set -eu
B=dc=acmeinsurance,dc=com
LDIF_DIR="${LDIF_DIR:-/seed}"
ADM="-x -H ldap://127.0.0.1:1389 -D cn=admin,$B -w ${LDAP_ADMIN_PASSWORD:?}"
CFG="-Q -Y EXTERNAL -H ldapi:///"
DB="olcDatabase={1}mdb,cn=config"
SVC="cn=svc-directory,ou=services,$B"
CLIENT="cn=insure-directory,ou=oauth-clients,ou=services,$B"
: "${SVC_DIRECTORY_PASSWORD:?}"

hash() {
  slappasswd -o module-path=/usr/lib/ldap -o module-load=argon2.la -h '{ARGON2}' -s "$1"
}
exists() { ldapsearch $ADM -b "$1" -s base -LLL 1.1 >/dev/null 2>&1; }
# The values of <attr> in the mdb entry of 10-config-db.ldif, unwrapped.
shipped() {
  awk '/^ / { line = line substr($0, 2); next } { if (line != "") print line; line = $0 } END { print line }' \
    "$LDIF_DIR/10-config-db.ldif" \
    | awk '/^dn: olcDatabase=\{1\}mdb,cn=config$/ { on = 1; next } /^dn: / { on = 0 } on' \
    | grep "^$1:"
}

[ -f "$LDIF_DIR/36-insure-directory.ldif" ] \
  || { echo "$LDIF_DIR has no 36-insure-directory.ldif: rebuild the openldap image first"; exit 1; }

# 1. The service account. Pre-hashed, as on first start.
if exists "$SVC"; then
  echo "account: $SVC already exists"
else
  ldapadd $ADM >/dev/null <<L
dn: $SVC
objectClass: organizationalRole
objectClass: simpleSecurityObject
cn: svc-directory
description: InsureDirectory (frontend/); maintains people, accounts, features and underwriting companies
userPassword: $(hash "$SVC_DIRECTORY_PASSWORD")
L
  echo "account: added $SVC"
fi

# 2. ACLs and limits, exactly as shipped.
if ldapsearch $CFG -b "$DB" -s base -LLL -o ldif-wrap=no olcAccess | grep -q 'cn=svc-directory,'; then
  echo "acl: already names svc-directory"
else
  tmp=$(mktemp)
  {
    echo "dn: $DB"
    echo "changetype: modify"
    echo "replace: olcLimits"
    shipped olcLimits
    echo "-"
    echo "replace: olcAccess"
    shipped olcAccess
    echo "-"
  } > "$tmp"
  grep -q 'cn=svc-directory,' "$tmp" || { echo "no svc-directory ACL in $LDIF_DIR/10-config-db.ldif"; exit 1; }
  ldapmodify $CFG -f "$tmp" >/dev/null
  rm -f "$tmp"
  echo "acl: olcAccess and olcLimits replaced from 10-config-db.ldif"
fi

# 3. The feature and its permissions.
if exists "cn=directory,ou=features,$B"; then
  echo "feature: cn=directory already exists"
else
  ldapadd $ADM -f "$LDIF_DIR/36-insure-directory.ldif" >/dev/null
  echo "feature: added cn=directory with view-entries, edit-entries, run-queries"
fi

# 4. The OAuth client.
if exists "$CLIENT"; then
  echo "client: $CLIENT already exists"
elif [ -z "${DIRECTORY_CLIENT_SECRET:-}" ]; then
  echo "client: DIRECTORY_CLIENT_SECRET not set; skipped"
else
  sed -e "s|__DIRECTORY_CLIENT_PW_HASH__|$(hash "$DIRECTORY_CLIENT_SECRET")|" \
    "$LDIF_DIR/37-oauth-insure-directory.ldif" | ldapadd $ADM >/dev/null
  echo "client: added $CLIENT"
fi
