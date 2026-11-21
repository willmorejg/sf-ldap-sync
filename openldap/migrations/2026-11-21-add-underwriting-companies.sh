#!/bin/sh
# Adds underwriting companies to a server built before 2026-11-21:
#   - schema: acmeUnderwritingCompanyDN (allowed on acmeAgreementAccount) and the
#     acmeUnderwritingCompany class, as in 01-acmeinsurance-schema.ldif
#   - an equality index, refint and a constraint for acmeUnderwritingCompanyDN,
#     as in 10-config-db.ldif
#   - the ou=underwritingCompanies branch, as in 20-dit.ldif
# Load the companies themselves afterwards (35-sample-underwriting-companies.ldif
# shows the format) as cn=admin.
#
# Run inside the openldap container (needs ldapi:/// as root for cn=config):
#   podman cp openldap/migrations/2026-11-21-add-underwriting-companies.sh sf-ldap-sync_openldap_1:/tmp/m.sh
#   podman exec -e LDAP_ADMIN_PASSWORD sf-ldap-sync_openldap_1 sh /tmp/m.sh
# Safe to run twice: each step checks whether it is already done.
set -eu
B=dc=acmeinsurance,dc=com
UWB="ou=underwritingCompanies,$B"
ADM="-x -H ldap://127.0.0.1:1389 -D cn=admin,$B -w ${LDAP_ADMIN_PASSWORD:?}"
CFG="-Q -Y EXTERNAL -H ldapi:///"
DB="olcDatabase={1}mdb,cn=config"
OID_ATTR=1.3.6.1.4.1.99999.1.1.14
OID_AGREEMENT=1.3.6.1.4.1.99999.1.2.6
OID_COMPANY=1.3.6.1.4.1.99999.1.2.14

values() { # values <dn> <attr>: every value, one per line, unwrapped
  ldapsearch $CFG -b "$1" -s base -LLL -o ldif-wrap=no "$2" | grep "^$2:" || true
}

# 1. Schema. The attribute must exist before a class can name it.
SCHEMA=$(ldapsearch $CFG -b cn=schema,cn=config -s one -LLL '(cn=*acmeinsurance)' 1.1 | sed -n 's/^dn: //p')
[ -n "$SCHEMA" ] || { echo "cn=acmeinsurance schema not found"; exit 1; }
if values "$SCHEMA" olcAttributeTypes | grep -q "$OID_ATTR "; then
  echo "schema: acmeUnderwritingCompanyDN already defined"
else
  ldapmodify $CFG >/dev/null <<L
dn: $SCHEMA
changetype: modify
add: olcAttributeTypes
olcAttributeTypes: ( $OID_ATTR NAME 'acmeUnderwritingCompanyDN' DESC 'DN of the underwriting company of an agreement' EQUALITY distinguishedNameMatch SYNTAX 1.3.6.1.4.1.1466.115.121.1.12 SINGLE-VALUE )
L
  echo "schema: added acmeUnderwritingCompanyDN"
fi
if values "$SCHEMA" olcObjectClasses | grep "$OID_AGREEMENT " | grep -q acmeUnderwritingCompanyDN; then
  echo "schema: acmeAgreementAccount already allows acmeUnderwritingCompanyDN"
else
  # Replace every class value, in order, with the agreement class extended.
  tmp=$(mktemp)
  {
    echo "dn: $SCHEMA"
    echo "changetype: modify"
    echo "replace: olcObjectClasses"
    values "$SCHEMA" olcObjectClasses \
      | sed "/$OID_AGREEMENT /s/STRUCTURAL *)\$/STRUCTURAL MAY acmeUnderwritingCompanyDN )/"
  } > "$tmp"
  grep "$OID_AGREEMENT " "$tmp" | grep -q acmeUnderwritingCompanyDN \
    || { echo "could not rewrite acmeAgreementAccount; see $tmp"; exit 1; }
  ldapmodify $CFG -f "$tmp" >/dev/null
  rm -f "$tmp"
  echo "schema: acmeAgreementAccount now allows acmeUnderwritingCompanyDN"
fi
if values "$SCHEMA" olcObjectClasses | grep -q "$OID_COMPANY "; then
  echo "schema: acmeUnderwritingCompany already defined"
else
  ldapmodify $CFG >/dev/null <<L
dn: $SCHEMA
changetype: modify
add: olcObjectClasses
olcObjectClasses: ( $OID_COMPANY NAME 'acmeUnderwritingCompany' DESC 'An underwriting company; agreements point at it with acmeUnderwritingCompanyDN' SUP top STRUCTURAL MUST cn MAY ( displayName \$ description ) )
L
  echo "schema: added acmeUnderwritingCompany"
fi

# 2. Index, referential integrity and constraint.
if values "$DB" olcDbIndex | grep -q acmeUnderwritingCompanyDN; then
  echo "index: already present"
else
  printf 'dn: %s\nchangetype: modify\nadd: olcDbIndex\nolcDbIndex: acmeUnderwritingCompanyDN eq\n' "$DB" \
    | ldapmodify $CFG >/dev/null
  echo "index: added acmeUnderwritingCompanyDN eq"
fi
overlay() { # overlay <objectClass>: DN of that overlay on the database
  d=$(ldapsearch $CFG -b "$DB" -s one -LLL "(objectClass=$1)" 1.1 | sed -n 's/^dn: //p')
  [ -n "$d" ] || { echo "no $1 overlay on $DB" >&2; exit 1; }
  echo "$d"
}
REFINT=$(overlay olcRefintConfig)
if values "$REFINT" olcRefintAttribute | grep -q acmeUnderwritingCompanyDN; then
  echo "refint: already covers acmeUnderwritingCompanyDN"
else
  printf 'dn: %s\nchangetype: modify\nadd: olcRefintAttribute\nolcRefintAttribute: acmeUnderwritingCompanyDN\n' \
    "$REFINT" | ldapmodify $CFG >/dev/null
  echo "refint: added acmeUnderwritingCompanyDN"
fi
CONSTRAINT=$(overlay olcConstraintConfig)
if values "$CONSTRAINT" olcConstraintAttribute | grep -q acmeUnderwritingCompanyDN; then
  echo "constraint: already covers acmeUnderwritingCompanyDN"
else
  ldapmodify $CFG >/dev/null <<L
dn: $CONSTRAINT
changetype: modify
add: olcConstraintAttribute
olcConstraintAttribute: acmeUnderwritingCompanyDN uri ldap:///$UWB?entryDN?one?(objectClass=acmeUnderwritingCompany)
L
  echo "constraint: added acmeUnderwritingCompanyDN"
fi

# 3. The branch.
if ldapsearch $ADM -b "$UWB" -s base -LLL 1.1 >/dev/null 2>&1; then
  echo "tree: $UWB already exists"
else
  ldapadd $ADM >/dev/null <<L
dn: $UWB
objectClass: organizationalUnit
ou: underwritingCompanies
description: Underwriting companies; agreements name theirs with acmeUnderwritingCompanyDN
L
  echo "tree: added $UWB"
fi
