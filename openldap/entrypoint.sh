#!/bin/sh
# First start: build cn=config and the directory tree from ./ldif, hashing the
# passwords supplied in the environment. Later starts: just run slapd.
set -eu

CONF_DIR="${CONF_DIR:-/etc/ldap/slapd.d}"
DATA_DIR="${DATA_DIR:-/var/lib/ldap}"
LDIF_DIR="${LDIF_DIR:-/seed}"
SCHEMA_DIR="${SCHEMA_DIR:-/etc/ldap/schema}"
RUN_DIR="${RUN_DIR:-/run/slapd}"
LDAP_URLS="${LDAP_URLS:-ldap://0.0.0.0:1389/ ldapi:///}"

hash() {
  slappasswd -o module-path=/usr/lib/ldap -o module-load=argon2.la -h '{ARGON2}' -s "$1"
}

need() {
  eval "v=\${$1:-}"
  [ -n "$v" ] || { echo "entrypoint: $1 must be set" >&2; exit 1; }
}

mkdir -p "$RUN_DIR"

if [ ! -e "$CONF_DIR/cn=config.ldif" ]; then
  need LDAP_ADMIN_PASSWORD; need SVC_SYNC_PASSWORD; need SVC_AUTH_PASSWORD
  echo "entrypoint: first start, building configuration"
  mkdir -p "$CONF_DIR" "$DATA_DIR"
  tmp=$(mktemp -d)

  admin_hash=$(hash "$LDAP_ADMIN_PASSWORD")
  {
    cat "$LDIF_DIR/00-config-global.ldif"
    for s in core cosine inetorgperson; do cat "$SCHEMA_DIR/$s.ldif"; echo; done
    cat "$LDIF_DIR/01-acmeinsurance-schema.ldif"; echo
    cat "$LDIF_DIR/02-acmeinsurance-sync-schema.ldif"; echo
    cat "$LDIF_DIR/03-acmeinsurance-auth-schema.ldif"; echo
    # Argon2 hashes never contain '|', so it is safe as the sed delimiter.
    sed -e "s|__ADMIN_PW_HASH__|$admin_hash|" \
        -e "s|/var/lib/ldap|$DATA_DIR|" "$LDIF_DIR/10-config-db.ldif"
  } | sed -e "s|/run/slapd|$RUN_DIR|" > "$tmp/config.ldif"
  slapadd -n0 -F "$CONF_DIR" -l "$tmp/config.ldif"

  sync_hash=$(hash "$SVC_SYNC_PASSWORD")
  auth_hash=$(hash "$SVC_AUTH_PASSWORD")
  sed -e "s|__SVC_SYNC_PW_HASH__|$sync_hash|" -e "s|__SVC_AUTH_PW_HASH__|$auth_hash|" \
      "$LDIF_DIR/20-dit.ldif" > "$tmp/dit.ldif"
  if [ "${LOAD_SAMPLES:-false}" = "true" ]; then
    { echo; cat "$LDIF_DIR/30-sample-clients.ldif"; } >> "$tmp/dit.ldif"
    { echo; cat "$LDIF_DIR/33-sample-oauth-public.ldif"; } >> "$tmp/dit.ldif"
    if [ -n "${OAUTH_DEMO_CLIENT_SECRET:-}" ]; then
      demo_hash=$(hash "$OAUTH_DEMO_CLIENT_SECRET")
      { echo; sed -e "s|__DEMO_CLIENT_PW_HASH__|$demo_hash|" "$LDIF_DIR/34-sample-oauth-confidential.ldif"; } >> "$tmp/dit.ldif"
    fi
  fi
  if [ "${LOAD_SAMPLES:-false}" = "true" ] || [ "${LOAD_SAMPLE_AGENCIES:-false}" = "true" ]; then
    { echo; cat "$LDIF_DIR/32-sample-features.ldif"; } >> "$tmp/dit.ldif"
  fi
  # Hand-made agencies and a sample agent, for trying the directory without
  # Salesforce. Leave off on a server the Salesforce sync feeds: the sync never
  # removes entries it does not own.
  if [ "${LOAD_SAMPLE_AGENCIES:-false}" = "true" ]; then
    { echo; cat "$LDIF_DIR/31-sample-agencies.ldif"; } >> "$tmp/dit.ldif"
  fi
  slapadd -n1 -F "$CONF_DIR" -l "$tmp/dit.ldif"
  rm -rf "$tmp"
fi

# phpLDAPadmin (nginx + php-fpm, both daemonize). slapd stays PID 1, so the
# container's life follows slapd; the web UI is a convenience on the side.
if [ "${PHPLDAPADMIN_ENABLED:-true}" = "true" ]; then
  mkdir -p /run/phpldapadmin /run/php
  ( umask 027; head -c 48 /dev/urandom | base64 | tr -d '\n' > /run/phpldapadmin/blowfish )
  chgrp www-data /run/phpldapadmin/blowfish
  if php-fpm8.3 --daemonize && nginx; then
    echo "entrypoint: phpLDAPadmin on port 8080"
  else
    echo "entrypoint: WARNING phpLDAPadmin failed to start; slapd starts anyway" >&2
  fi
fi

# Run slapd as the openldap user when started as root.
set --
if [ "$(id -u)" = "0" ] && id openldap >/dev/null 2>&1; then
  chown -R openldap:openldap "$CONF_DIR" "$DATA_DIR" "$RUN_DIR"
  set -- -u openldap -g openldap
fi

echo "entrypoint: starting slapd on $LDAP_URLS"
exec slapd "$@" -d "${SLAPD_DEBUG:-stats}" -F "$CONF_DIR" -h "$LDAP_URLS"
