#!/bin/sh
# For the single backend container (backend/, port 8080), which replaced the sync,
# auth and InsureDirectory containers on 2026-11-21:
#   - cn=insure-directory: the redirect URI http://localhost:8082/auth/callback becomes
#     http://localhost:8080/auth/callback (37-oauth-insure-directory.ldif)
#   - the sync feature, whose run-sync permission the /sync trigger now requires
#     (38-sync-feature.ldif)
# Grant cn=run-sync,cn=sync,ou=features to whoever runs the sync by hand.
#
# Run inside the openldap container (after 2026-11-21-add-insure-directory.sh):
#   podman cp openldap/migrations/2026-11-21-consolidate-backend.sh sf-ldap-sync_openldap_1:/tmp/m.sh
#   podman exec -e LDAP_ADMIN_PASSWORD sf-ldap-sync_openldap_1 sh /tmp/m.sh
# Safe to run twice: each step checks whether it is already done.
set -eu
B=dc=acmeinsurance,dc=com
ADM="-x -H ldap://127.0.0.1:1389 -D cn=admin,$B -w ${LDAP_ADMIN_PASSWORD:?}"
CLIENT="cn=insure-directory,ou=oauth-clients,ou=services,$B"
OLD_URI=http://localhost:8082/auth/callback
NEW_URI=http://localhost:8080/auth/callback
SYNC="cn=sync,ou=features,$B"
LDIF_DIR="${LDIF_DIR:-/seed}"

exists() { # exists <dn>
  ldapsearch $ADM -b "$1" -s base -LLL 1.1 >/dev/null 2>&1
}
has_uri() { # has_uri <uri>
  ldapsearch $ADM -b "$CLIENT" -s base -LLL -o ldif-wrap=no acmeRedirectUri | grep -qx "acmeRedirectUri: $1"
}

# 1. Redirect URI of InsureDirectory's OAuth client.
if ! exists "$CLIENT"; then
  echo "redirect URI: $CLIENT does not exist (no DIRECTORY_CLIENT_SECRET when it was built); skipped"
else
  if has_uri "$NEW_URI"; then
    echo "redirect URI: $NEW_URI already registered"
  else
    printf 'dn: %s\nchangetype: modify\nadd: acmeRedirectUri\nacmeRedirectUri: %s\n' "$CLIENT" "$NEW_URI" \
      | ldapmodify $ADM >/dev/null
    echo "redirect URI: added $NEW_URI"
  fi
  if has_uri "$OLD_URI"; then
    printf 'dn: %s\nchangetype: modify\ndelete: acmeRedirectUri\nacmeRedirectUri: %s\n' "$CLIENT" "$OLD_URI" \
      | ldapmodify $ADM >/dev/null
    echo "redirect URI: removed $OLD_URI"
  else
    echo "redirect URI: $OLD_URI not registered"
  fi
fi

# 2. The sync feature.
if exists "$SYNC"; then
  echo "feature: $SYNC already exists"
else
  ldapadd $ADM -f "$LDIF_DIR/38-sync-feature.ldif" >/dev/null
  echo "feature: added $SYNC with run-sync"
fi
