#!/usr/bin/env bash
# decision: D00-4 — docs/step_00_foundations.md#decisions-and-outputs
# Entrypoint wrapper for infra/postgres/init.sql. The postgres image runs it once, on an empty data volume.
# SQL cannot read environment variables, so role passwords are passed to psql as variables (-v) and quoted
# with :'name' in SQL; no literal secret is committed. Any failure exits non-zero, which stops the container
# instead of leaving a half-initialized database that reports healthy.
set -euo pipefail

required=(
  ZS_ORDERS_OWNER_DB_PASSWORD ZS_ORDERS_APP_DB_PASSWORD
  ZS_LEDGER_OWNER_DB_PASSWORD ZS_LEDGER_APP_DB_PASSWORD
  ZS_INSTRUMENTS_OWNER_DB_PASSWORD ZS_INSTRUMENTS_APP_DB_PASSWORD
  ZS_FAKEPROVIDERS_OWNER_DB_PASSWORD ZS_FAKEPROVIDERS_APP_DB_PASSWORD
  ZS_VERIFIER_DB_PASSWORD ZS_STATS_DB_PASSWORD
)
for name in "${required[@]}"; do
  if [[ -z "${!name:-}" ]]; then
    echo "init.sh: required variable $name is not set" >&2
    exit 1
  fi
done

psql --no-psqlrc -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
  -v orders_owner_password="$ZS_ORDERS_OWNER_DB_PASSWORD" \
  -v orders_app_password="$ZS_ORDERS_APP_DB_PASSWORD" \
  -v ledger_owner_password="$ZS_LEDGER_OWNER_DB_PASSWORD" \
  -v ledger_app_password="$ZS_LEDGER_APP_DB_PASSWORD" \
  -v instruments_owner_password="$ZS_INSTRUMENTS_OWNER_DB_PASSWORD" \
  -v instruments_app_password="$ZS_INSTRUMENTS_APP_DB_PASSWORD" \
  -v fakeproviders_owner_password="$ZS_FAKEPROVIDERS_OWNER_DB_PASSWORD" \
  -v fakeproviders_app_password="$ZS_FAKEPROVIDERS_APP_DB_PASSWORD" \
  -v verifier_password="$ZS_VERIFIER_DB_PASSWORD" \
  -v stats_password="$ZS_STATS_DB_PASSWORD" \
  -f /zs/postgres/init.sql
